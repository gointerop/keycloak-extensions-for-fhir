/*
(C) Copyright IBM Corp. 2021

SPDX-License-Identifier: Apache-2.0
*/
package org.alvearie.keycloak;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.alvearie.keycloak.freemarker.PatientStruct;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.Urls;
import org.keycloak.services.util.DefaultClientSessionContext;
import org.keycloak.sessions.AuthenticationSessionModel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Present a patient context picker when the client requests the launch/patient scope and the
 * user record has multiple resourceId attributes. The selection is stored in a UserSessionNote
 * with name "patient_id".
 */
public class PatientSelectionForm implements Authenticator {

    private static final Logger LOG = Logger.getLogger(PatientSelectionForm.class);

    private static final String SMART_AUDIENCE_PARAM = "client_request_param_aud";
    private static final String SMART_SCOPE_PATIENT_READ = "patient/Patient.read";
    private static final String SMART_SCOPE_LAUNCH_PATIENT = "launch/patient";

    private static final String ATTRIBUTE_RESOURCE_ID = "resourceId";

    private static final String FHIR_JSON = "application/fhir+json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        ClientModel client = authSession.getClient();

        String requestedScopesString = authSession.getClientNote(OIDCLoginProtocol.SCOPE_PARAM);
        Stream<ClientScopeModel> clientScopes = TokenManager.getRequestedClientScopes(context.getSession(),
                requestedScopesString, client, context.getUser());

        if (clientScopes.noneMatch(s -> SMART_SCOPE_LAUNCH_PATIENT.equals(s.getName()))) {
            // no launch/patient scope == no-op
            context.success();
            return;
        }

        if (context.getUser() == null) {
            fail(context, "Expected a user but found null");
            return;
        }

        List<String> resourceIds = getResourceIdsForUser(context);
        if (resourceIds.size() == 0) {
            fail(context, "Expected user to have one or more resourceId attributes, but found none");
            return;
        }
        if (resourceIds.size() == 1) {
            succeed(context, resourceIds.get(0));
            return;
        }

        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        if (config == null || !config.getConfig().containsKey(PatientSelectionFormFactory.INTERNAL_FHIR_URL_PROP_NAME)) {
            fail(context, "The Patient Selection Authenticator must be configured with a valid FHIR base URL");
            return;
        }

        String accessToken = buildInternalAccessToken(context, resourceIds);

        HttpResponse<String> fhirResponse;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getConfig().get(PatientSelectionFormFactory.INTERNAL_FHIR_URL_PROP_NAME)))
                    .timeout(Duration.ofSeconds(30))
                    .header(HttpHeaders.ACCEPT, FHIR_JSON + ", application/json")
                    .header(HttpHeaders.CONTENT_TYPE, FHIR_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBundle(resourceIds)))
                    .build();
            fhirResponse = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.warn("Error while calling the FHIR server for the selection form", e);
            fail(context, "Error while retrieving Patient resources for the selection form");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(context, "Error while retrieving Patient resources for the selection form");
            return;
        }

        if (fhirResponse.statusCode() != 200) {
            LOG.warnf("Response with code %d%n%s", fhirResponse.statusCode(), fhirResponse.body());
            fail(context, "Error while retrieving Patient resources for the selection form");
            return;
        }

        List<PatientStruct> patients;
        try {
            patients = gatherPatientInfo(MAPPER.readTree(fhirResponse.body()));
        } catch (IOException e) {
            LOG.warn("Unable to parse the FHIR batch response", e);
            fail(context, "Error while retrieving Patient resources for the selection form");
            return;
        }

        if (patients.isEmpty()) {
            succeed(context, resourceIds.get(0));
            return;
        }

        if (patients.size() == 1) {
            succeed(context, patients.get(0).getId());
        } else {
            Response response = context.form()
                    .setAttribute("patients", patients)
                    .createForm("patient-select-form.ftl");

            context.challenge(response);
        }
    }

    private List<String> getResourceIdsForUser(AuthenticationFlowContext context) {
        return context.getUser().getAttributeStream(ATTRIBUTE_RESOURCE_ID)
                .flatMap(a -> Arrays.stream(a.split(" ")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String buildInternalAccessToken(AuthenticationFlowContext context, List<String> resourceIds) {
        KeycloakSession session = context.getSession();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        UserModel user = context.getUser();
        ClientModel client = authSession.getClient();

        // A throwaway session that only lives for this request; it must not be persisted
        UserSessionModel userSession = session.sessions().createUserSession(KeycloakModelUtils.generateId(),
                context.getRealm(), user, user.getUsername(), context.getConnection().getRemoteAddr(), null, false,
                null, null, UserSessionModel.SessionPersistenceState.TRANSIENT);

        AuthenticatedClientSessionModel authedClientSession = userSession.getAuthenticatedClientSessionByClient(client.getId());
        if (authedClientSession == null) {
            authedClientSession = session.sessions().createClientSession(context.getRealm(), client, userSession);
        }
        authedClientSession.setNote(OIDCLoginProtocol.ISSUER,
                Urls.realmIssuer(session.getContext().getUri().getBaseUri(), context.getRealm().getName()));

        // Note: this depends on the corresponding string being registered as a valid scope for this client
        ClientSessionContext clientSessionCtx = DefaultClientSessionContext.fromClientSessionAndScopeParameter(authedClientSession,
                SMART_SCOPE_PATIENT_READ, session);

        String requestedAudience = authSession.getClientNote(SMART_AUDIENCE_PARAM);
        if (requestedAudience == null) {
            String internalFhirUrl = context.getAuthenticatorConfig().getConfig().get(PatientSelectionFormFactory.INTERNAL_FHIR_URL_PROP_NAME);
            LOG.info("Client request is missing the 'aud' parameter, using '" + internalFhirUrl + "' from config.");
            requestedAudience = internalFhirUrl;
        }

        // Explicit decision not to check the requested audience against the configured internal FHIR URL
        // Checking of the requested audience should be performed in a previous step by the AudienceValidator
        TokenManager tokenManager = new TokenManager();
        AccessToken accessToken = tokenManager.createClientAccessToken(session, context.getRealm(), authSession.getClient(),
                context.getUser(), userSession, clientSessionCtx, false);

        // Explicitly override the scope string with what we need (less brittle than depending on this to exist as a client scope)
        accessToken.setScope(SMART_SCOPE_PATIENT_READ);

        JsonWebToken jwt = accessToken.audience(requestedAudience);
        jwt.setOtherClaims("patient_id", resourceIds);
        return session.tokens().encode(jwt);
    }

    /**
     * Build a FHIR batch Bundle with one "GET Patient/[id]" entry per resource id.
     */
    static String buildRequestBundle(List<String> resourceIds) {
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "batch");
        ArrayNode entries = bundle.putArray("entry");
        for (String id : resourceIds) {
            ObjectNode request = entries.addObject().putObject("request");
            request.put("method", "GET");
            request.put("url", "Patient/" + id);
        }
        return bundle.toString();
    }

    private void fail(AuthenticationFlowContext context, String msg) {
        LOG.warn(msg);
        context.failure(AuthenticationFlowError.INTERNAL_ERROR,
                Response.status(302)
                .header("Location", context.getAuthenticationSession().getRedirectUri() +
                        "?error=server_error" +
                        "&error_description=" + msg)
                .build());
    }

    private void succeed(AuthenticationFlowContext context, String patient) {
        // Add selected information to authentication session
        context.getAuthenticationSession().setUserSessionNote("patient_id", patient);
        context.success();
    }

    /**
     * Extract id, display name and birth date from each successful Patient entry of a FHIR batch-response Bundle.
     */
    static List<PatientStruct> gatherPatientInfo(JsonNode bundle) {
        List<PatientStruct> patients = new ArrayList<>();

        for (JsonNode entry : bundle.path("entry")) {
            if (!entry.path("response").path("status").asText("").startsWith("200")) {
                continue;
            }

            JsonNode patient = entry.path("resource");
            if (!"Patient".equals(patient.path("resourceType").asText())) {
                continue;
            }

            String patientId = patient.path("id").asText();

            String patientName = "Missing Name";
            JsonNode names = patient.path("name");
            if (names.isEmpty()) {
                LOG.warn("Patient[id=" + patientId + "] has no name; using placeholder");
            } else {
                if (names.size() > 1) {
                    LOG.warn("Patient[id=" + patientId + "] has multiple names; using the first one");
                }
                patientName = constructSimpleName(names.get(0));
            }

            // FHIR dates are already serialized as YYYY, YYYY-MM or YYYY-MM-DD
            String patientDOB = patient.path("birthDate").asText("missing");

            patients.add(new PatientStruct(patientId, patientName, patientDOB));
        }

        return patients;
    }

    private static String constructSimpleName(JsonNode name) {
        if (name.hasNonNull("text") && !name.get("text").asText().isEmpty()) {
            return name.get("text").asText();
        }

        List<String> parts = new ArrayList<>();
        name.path("given").forEach(g -> parts.add(g.asText()));
        if (name.hasNonNull("family")) {
            parts.add(name.get("family").asText());
        }
        return String.join(" ", parts);
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void action(AuthenticationFlowContext context) {

        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        String patient = formData.getFirst("patient");

        LOG.debugf("The user selected patient '%s'", patient);

        if (patient == null || patient.trim().isEmpty() || !getResourceIdsForUser(context).contains(patient.trim())) {
            LOG.warnf("The patient selection '%s' is not valid for the authenticated user.", patient);
            context.cancelLogin();

            // reauthenticate...
            authenticate(context);
            return;
        }

        succeed(context, patient.trim());
    }

    @Override
    public void close() {
    }
}

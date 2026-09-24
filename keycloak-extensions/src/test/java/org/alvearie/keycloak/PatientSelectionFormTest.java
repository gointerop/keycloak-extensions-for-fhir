/*
(C) Copyright IBM Corp. 2021

SPDX-License-Identifier: Apache-2.0
*/
package org.alvearie.keycloak;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.alvearie.keycloak.freemarker.PatientStruct;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PatientSelectionFormTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testBuildRequestBundle() throws Exception {
        JsonNode bundle = MAPPER.readTree(PatientSelectionForm.buildRequestBundle(Arrays.asList("PatientA", "PatientB")));

        assertEquals("Bundle", bundle.get("resourceType").asText());
        assertEquals("batch", bundle.get("type").asText());
        assertEquals(2, bundle.get("entry").size());
        assertEquals("GET", bundle.get("entry").get(0).get("request").get("method").asText());
        assertEquals("Patient/PatientA", bundle.get("entry").get(0).get("request").get("url").asText());
        assertEquals("Patient/PatientB", bundle.get("entry").get(1).get("request").get("url").asText());
    }

    @Test
    public void testGatherPatientInfo() throws Exception {
        JsonNode response = MAPPER.readTree(IOUtils.resourceToString("/mock_fhir_response.json", StandardCharsets.UTF_8));

        List<PatientStruct> patients = PatientSelectionForm.gatherPatientInfo(response);

        // the 404 entry for PatientMissing is skipped
        assertEquals(2, patients.size());
        assertEquals("PatientB", patients.get(0).getId());
        assertEquals("Robert Patient", patients.get(0).getName());
        assertEquals("2000-01-01", patients.get(0).getDob());
        assertEquals("PatientA", patients.get(1).getId());
        assertEquals("Alice Patient", patients.get(1).getName());
        assertEquals("1974-12-25", patients.get(1).getDob());
    }

    @Test
    public void testGatherPatientInfoWithTextNameAndMissingFields() throws Exception {
        JsonNode response = MAPPER.readTree("{\"resourceType\":\"Bundle\",\"type\":\"batch-response\",\"entry\":["
                + "{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"p1\",\"name\":[{\"text\":\"Dr. Jane Doe\",\"family\":\"Doe\"}]},"
                + "\"response\":{\"status\":\"200 OK\"}},"
                + "{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"p2\"},\"response\":{\"status\":\"200\"}}]}");

        List<PatientStruct> patients = PatientSelectionForm.gatherPatientInfo(response);

        assertEquals(2, patients.size());
        assertEquals("Dr. Jane Doe", patients.get(0).getName());
        assertEquals("missing", patients.get(0).getDob());
        assertEquals("Missing Name", patients.get(1).getName());
    }
}

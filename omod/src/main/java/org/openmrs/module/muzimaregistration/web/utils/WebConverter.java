/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.muzimaregistration.web.utils;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.muzimaregistration.api.model.RegistrationData;

import java.util.HashMap;
import java.util.Map;

/**
 * TODO: Write brief description about the class here.
 */
public class WebConverter {
    public static Map<String, Object> convertRegistrationData(final RegistrationData registrationData) {
        Map<String, Object> map = new HashMap<String, Object>();
        if (registrationData != null) {
            map.put("uuid", registrationData.getUuid());
            map.put("assignedUuid", registrationData.getAssignedUuid());

            Patient patient = Context.getPatientService().getPatientByUuid(registrationData.getAssignedUuid());
            Map<String, Object> patientMap = new HashMap<String, Object>();
            patientMap.put("name", patient.getPersonName().getFullName());
            patientMap.put("gender", patient.getGender());
            patientMap.put("birthdate", Context.getDateFormat().format(patient.getBirthdate()));
            patientMap.put("identifier", patient.getPatientIdentifier().getIdentifier());
            map.put("patient", patientMap);

            map.put("temporaryUuid", registrationData.getTemporaryUuid());
            map.put("submitted", Context.getDateFormat().format(registrationData.getDateCreated()));
        }
        return map;
    }
}

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
package org.openmrs.module.muzimaregistration.handler;

import net.minidev.json.JSONArray;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PersonName;
import org.openmrs.annotation.Handler;
import org.openmrs.api.LocationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.muzima.exception.QueueProcessorException;
import org.openmrs.module.muzima.model.QueueData;
import org.openmrs.module.muzima.model.handler.QueueDataHandler;
import org.openmrs.module.muzimaregistration.api.RegistrationDataService;
import org.openmrs.module.muzimaregistration.api.model.RegistrationData;
import org.openmrs.module.muzimaregistration.utils.JsonUtils;

import java.util.Date;
import java.util.List;

/**
 * TODO: Write brief description about the class here.
 */
@Handler(supports = QueueData.class, order = 50)
public class RegistrationQueueDataHandler implements QueueDataHandler {

    private static final String DISCRIMINATOR_VALUE = "registration";

    private final Log log = LogFactory.getLog(RegistrationQueueDataHandler.class);

    private PatientService patientService;
    private LocationService locationService;

    /**
     * Implementation of how the queue data should be processed.
     *
     * @param queueData the queued data.
     * @should create new patient from well formed registration data
     * @should skip already processed registration data
     */
    @Override
    public void process(final QueueData queueData) throws QueueProcessorException {
        log.info("Processing registration form data: " + queueData.getUuid());
        String payload = queueData.getPayload();

        RegistrationDataService registrationDataService = Context.getService(RegistrationDataService.class);

        RegistrationData registrationData;
        String temporaryUuid = getStringFromJSON(payload, "patient.uuid");
        if (StringUtils.isNotEmpty(temporaryUuid)) {
            registrationData = registrationDataService.getRegistrationDataByTemporaryUuid(temporaryUuid);
            if (registrationData == null) {
                // we can't find registration data for this uuid, process the registration form.

                patientService = Context.getPatientService();
                locationService = Context.getLocationService();

                Patient unsavedPatient = createPatientFromPayload(payload);

                Patient savedPatient;
                // check whether we already have similar patients!
                String identifier = unsavedPatient.getPatientIdentifier().getIdentifier();
                if (!StringUtils.isBlank(identifier)) {
                    List<Patient> patients = patientService.getPatients(identifier);
                    savedPatient = findPatient(patients, unsavedPatient);
                } else {
                    List<Patient> patients = patientService.getPatients(unsavedPatient.getPersonName().getFullName());
                    savedPatient = findPatient(patients, unsavedPatient);
                }

                registrationData = new RegistrationData();
                registrationData.setTemporaryUuid(temporaryUuid);
                String assignedUuid;
                // for a new patient we will create mapping:
                // * temporary uuid --> uuid of the newly created patient
                // for existing patient we will create mapping:
                // * temporary uuid --> uuid of the existing patient
                if (savedPatient != null) {
                    // if we have a patient already saved with the characteristic found in the registration form:
                    // * we will map the temporary uuid to the existing uuid.
                    assignedUuid = savedPatient.getUuid();
                } else {
                    patientService.savePatient(unsavedPatient);
                    assignedUuid = unsavedPatient.getUuid();
                }
                registrationData.setAssignedUuid(assignedUuid);
                registrationDataService.saveRegistrationData(registrationData);
            }
        }
    }

    /**
     * Flag whether the current queue data handler can handle the queue data.
     *
     * @param queueData the queue data.
     * @return true when the handler can handle the queue data.
     */
    @Override
    public boolean accept(final QueueData queueData) {
        return StringUtils.equals(DISCRIMINATOR_VALUE, queueData.getDiscriminator());
    }

    private Patient createPatientFromPayload(final String payload) {
        Patient patient = new Patient();
        PatientIdentifier patientIdentifier = new PatientIdentifier();
        patientIdentifier.setLocation(locationService.getLocation(Integer.parseInt(getStringFromJSON(payload, "encounter.location_id"))));
        patientIdentifier.setIdentifierType(patientService.getPatientIdentifierType(Integer.parseInt(getStringFromJSON(payload, "patient_identifier.identifier_type_id"))));
        patientIdentifier.setIdentifier(getStringFromJSON(payload, "patient.medical_record_number"));
        patient.addIdentifier(patientIdentifier);

        patient.setBirthdate(getDateFromJSON(payload, "patient.birthdate"));
        patient.setBirthdateEstimated(getBooleanFromJSON(payload, "patient.birthdate_estimated"));
        patient.setGender(getStringFromJSON(payload, "patient.sex"));

        PersonName personName = new PersonName();
        personName.setGivenName(getStringFromJSON(payload, "patient.given_name"));
        personName.setMiddleName(getStringFromJSON(payload, "patient.middle_name"));
        personName.setFamilyName(getStringFromJSON(payload, "patient.family_name"));
        patient.addName(personName);
        return patient;
    }

    private Object getObjectFromPayload(final String payload, final String name) {
        return JsonUtils.readAsObject(payload, "$['form']['fields'][?(@.name == '" + name + "')]");
    }

    private Boolean getBooleanFromJSON(final String payload, final String name) {
        Object object = getObjectFromPayload(payload, name);
        return JsonUtils.readAsBoolean(String.valueOf(((JSONArray) object).get(0)), "$['value']");
    }

    private String getStringFromJSON(final String payload, final String name) {
        Object object = getObjectFromPayload(payload, name);
        return JsonUtils.readAsString(String.valueOf(((JSONArray) object).get(0)), "$['value']");
    }

    private Date getDateFromJSON(final String payload, final String name) {
        Object object = getObjectFromPayload(payload, name);
        return JsonUtils.readAsDate(String.valueOf(((JSONArray) object).get(0)), "$['value']");
    }

    private Patient findPatient(final List<Patient> patients, final Patient unsavedPatient) {
        for (Patient patient : patients) {
            // match it using the person name and gender, what about the dob?
            PersonName savedPersonName = patient.getPersonName();
            PersonName unsavedPersonName = unsavedPatient.getPersonName();
            if (StringUtils.isNotBlank(savedPersonName.getFullName())
                    && StringUtils.isNotBlank(unsavedPersonName.getFullName())) {
                if (StringUtils.equalsIgnoreCase(patient.getGender(), unsavedPatient.getGender())) {
                    if (patient.getBirthdate() != null && unsavedPatient.getBirthdate() != null
                            && patient.getBirthdate().equals(unsavedPatient.getBirthdate())) {
                        String savedGivenName = savedPersonName.getGivenName();
                        String unsavedGivenName = unsavedPersonName.getGivenName();
                        int givenNameEditDistance = StringUtils.getLevenshteinDistance(
                                StringUtils.lowerCase(savedGivenName),
                                StringUtils.lowerCase(unsavedGivenName));
                        String savedFamilyName = savedPersonName.getFamilyName();
                        String unsavedFamilyName = unsavedPersonName.getFamilyName();
                        int familyNameEditDistance = StringUtils.getLevenshteinDistance(
                                StringUtils.lowerCase(savedFamilyName),
                                StringUtils.lowerCase(unsavedFamilyName));
                        if (givenNameEditDistance < 3 && familyNameEditDistance < 3) {
                            for (PatientIdentifier savedIdentifier : patient.getActiveIdentifiers()) {
                                PatientIdentifier unsavedIdentifier = unsavedPatient.getPatientIdentifier();
                                if (savedIdentifier.getIdentifierType().equals(unsavedIdentifier.getIdentifierType())) {
                                    if (StringUtils.equalsIgnoreCase(unsavedIdentifier.getIdentifier(), savedIdentifier.getIdentifier())) {
                                        return patient;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

}

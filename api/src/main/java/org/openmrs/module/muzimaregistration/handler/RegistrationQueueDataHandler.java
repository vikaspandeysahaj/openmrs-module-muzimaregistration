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

import com.jayway.jsonpath.JsonPath;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PersonAddress;
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

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * TODO: Write brief description about the class here.
 */
@Handler(supports = QueueData.class, order = 50)
public class RegistrationQueueDataHandler implements QueueDataHandler {

    private static final String DISCRIMINATOR_VALUE = "registration";

    private final Log log = LogFactory.getLog(RegistrationQueueDataHandler.class);

    /**
     * Implementation of how the queue data should be processed.
     *
     * @param queueData the queued data.
     */
    @Override
    public void process(final QueueData queueData) throws QueueProcessorException {
        log.info("Processing registration form data: " + queueData.getUuid());
        String payload = queueData.getPayload();

        RegistrationDataService registrationDataService = Context.getService(RegistrationDataService.class);

        String temporaryUuid = JsonPath.read(payload, "$['patient.uuid']");
        RegistrationData registrationData = registrationDataService.getRegistrationDataByTemporaryUuid(temporaryUuid);
        if (registrationData == null) {
            // we can't find registration data for this uuid, process the registration form.
            Patient unsavedPatient = new Patient();

            PatientService patientService = Context.getPatientService();
            LocationService locationService = Context.getLocationService();

            String identifier = JsonUtils.readAsString(payload, "$['patient.identifier']");
            String identifierTypeUuid = JsonUtils.readAsString(payload, "$['patient.identifier_type']");
            String locationUuid = JsonUtils.readAsString(payload, "$['patient.identifier_location']");

            PatientIdentifier patientIdentifier = new PatientIdentifier();
            patientIdentifier.setLocation(locationService.getLocationByUuid(locationUuid));
            patientIdentifier.setIdentifierType(patientService.getPatientIdentifierTypeByUuid(identifierTypeUuid));
            patientIdentifier.setIdentifier(identifier);
            unsavedPatient.addIdentifier(patientIdentifier);

            Date birthdate = JsonUtils.readAsDate(payload, "$['patient.birthdate']");
            boolean birthdateEstimated = JsonUtils.readAsBoolean(payload, "$['patient.birthdate_estimated']");
            String gender = JsonUtils.readAsString(payload, "$['patient.gender']");

            unsavedPatient.setBirthdate(birthdate);
            unsavedPatient.setBirthdateEstimated(birthdateEstimated);
            unsavedPatient.setGender(gender);

            String givenName = JsonUtils.readAsString(payload, "$['patient.given_name']");
            String middleName = JsonUtils.readAsString(payload, "$['patient.middle_name']");
            String familyName = JsonUtils.readAsString(payload, "$['patient.family_name']");

            PersonName personName = new PersonName();
            personName.setGivenName(givenName);
            personName.setMiddleName(middleName);
            personName.setFamilyName(familyName);
            unsavedPatient.addName(personName);

            String address1 = JsonUtils.readAsString(payload, "$['person_address.address1']");
            String address2 = JsonUtils.readAsString(payload, "$['person_address.address2']");

            PersonAddress personAddress = new PersonAddress();
            personAddress.setAddress1(address1);
            personAddress.setAddress2(address2);
            unsavedPatient.addAddress(personAddress);

            Patient savedPatient;
            // check whether we already have similar patients!
            if (!StringUtils.isBlank(identifier)) {
                List<Patient> patients = patientService.getPatients(identifier);
                savedPatient = findPatient(patients, unsavedPatient);
            } else {
                List<Patient> patients = patientService.getPatients(personName.getFullName());
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
                Context.getPatientService().savePatient(unsavedPatient);
                assignedUuid = unsavedPatient.getUuid();
            }
            registrationData.setAssignedUuid(assignedUuid);
            registrationDataService.saveRegistrationData(registrationData);
        }
    }

    private Patient findPatient(final List<Patient> patients, final Patient unsavedPatient) {
        for (Patient patient : patients) {
            PatientIdentifier savedIdentifier = patient.getPatientIdentifier();
            PatientIdentifier unsavedIdentifier = unsavedPatient.getPatientIdentifier();
            if (StringUtils.isNotBlank(savedIdentifier.getIdentifier())
                    && StringUtils.isNotBlank(unsavedIdentifier.getIdentifier())) {
                int editDistance = StringUtils.getLevenshteinDistance(
                        StringUtils.lowerCase(savedIdentifier.getIdentifier()),
                        StringUtils.lowerCase(unsavedIdentifier.getIdentifier()));
                // exact match on the patient identifier, they are the same patient.
                if (editDistance == 0) {
                    return patient;
                }
            }
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
                            return patient;
                        }
                    }
                }
            }
        }
        return null;
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
}

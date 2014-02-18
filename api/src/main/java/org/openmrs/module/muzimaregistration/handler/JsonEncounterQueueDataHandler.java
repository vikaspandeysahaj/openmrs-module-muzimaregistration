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
import net.minidev.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Form;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PersonAddress;
import org.openmrs.PersonName;
import org.openmrs.User;
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
import org.springframework.stereotype.Component;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 */
@Component
@Handler(supports = QueueData.class, order = 2)
public class JsonEncounterQueueDataHandler implements QueueDataHandler {

    private static final String DISCRIMINATOR_VALUE = "json-encounter";

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    private final Log log = LogFactory.getLog(JsonEncounterQueueDataHandler.class);

    @Override

    public void process(final QueueData queueData) throws QueueProcessorException {
        log.info("Processing encounter form data: " + queueData.getUuid());
        String payload = queueData.getPayload();

        Encounter encounter = new Encounter();

        Object patientObject = JsonUtils.readAsObject(queueData.getPayload(), "$['patient']");
        processPatient(encounter, patientObject);

        Object encounterObject = JsonUtils.readAsObject(queueData.getPayload(), "$['encounter']");
        processEncounter(encounter, encounterObject);

        Object obsObject = JsonUtils.readAsObject(queueData.getPayload(), "$['observation']");
        processObs(encounter, null, obsObject);

        Context.getEncounterService().saveEncounter(encounter);
    }

    private void processPatient(final Encounter encounter, final Object patientObject) throws QueueProcessorException {
        Patient unsavedPatient = new Patient();
        String patientPayload = patientObject.toString();

        PatientService patientService = Context.getPatientService();
        LocationService locationService = Context.getLocationService();

        String identifier = JsonUtils.readAsString(patientPayload, "$['patient.identifier']");
        String identifierTypeUuid = JsonUtils.readAsString(patientPayload, "$['patient.identifier_type']");
        String locationUuid = JsonUtils.readAsString(patientPayload, "$['patient.identifier_location']");

        PatientIdentifier patientIdentifier = new PatientIdentifier();
        patientIdentifier.setLocation(locationService.getLocationByUuid(locationUuid));
        patientIdentifier.setIdentifierType(patientService.getPatientIdentifierTypeByUuid(identifierTypeUuid));
        patientIdentifier.setIdentifier(identifier);
        unsavedPatient.addIdentifier(patientIdentifier);

        Date birthdate = JsonUtils.readAsDate(patientPayload, "$['patient.birthdate']");
        boolean birthdateEstimated = JsonUtils.readAsBoolean(patientPayload, "$['patient.birthdate_estimated']");
        String gender = JsonUtils.readAsString(patientPayload, "$['patient.gender']");

        unsavedPatient.setBirthdate(birthdate);
        unsavedPatient.setBirthdateEstimated(birthdateEstimated);
        unsavedPatient.setGender(gender);

        String givenName = JsonUtils.readAsString(patientPayload, "$['patient.given_name']");
        String middleName = JsonUtils.readAsString(patientPayload, "$['patient.middle_name']");
        String familyName = JsonUtils.readAsString(patientPayload, "$['patient.family_name']");

        PersonName personName = new PersonName();
        personName.setGivenName(givenName);
        personName.setMiddleName(middleName);
        personName.setFamilyName(familyName);

        String address1 = JsonUtils.readAsString(patientPayload, "$['person_address.address1']");
        String address2 = JsonUtils.readAsString(patientPayload, "$['person_address.address2']");

        PersonAddress personAddress = new PersonAddress();
        personAddress.setAddress1(address1);
        personAddress.setAddress2(address2);

        unsavedPatient.addName(personName);
        unsavedPatient.addIdentifier(patientIdentifier);

        Patient candidatePatient;
        if (StringUtils.isNotEmpty(unsavedPatient.getUuid())) {
            candidatePatient = Context.getPatientService().getPatientByUuid(unsavedPatient.getUuid());
            if (candidatePatient == null) {
                String temporaryUuid = unsavedPatient.getUuid();
                RegistrationDataService dataService = Context.getService(RegistrationDataService.class);
                RegistrationData registrationData = dataService.getRegistrationDataByTemporaryUuid(temporaryUuid);
                candidatePatient = Context.getPatientService().getPatientByUuid(registrationData.getAssignedUuid());
            }
        } else if (!StringUtils.isBlank(patientIdentifier.getIdentifier())) {
            List<Patient> patients = Context.getPatientService().getPatients(patientIdentifier.getIdentifier());
            candidatePatient = findPatient(patients, unsavedPatient);
        } else {
            List<Patient> patients = Context.getPatientService().getPatients(unsavedPatient.getPersonName().getFullName());
            candidatePatient = findPatient(patients, unsavedPatient);
        }

        if (candidatePatient == null) {
            throw new QueueProcessorException("Unable to uniquely identify a patient for this encounter form data.");
        }

        encounter.setPatient(candidatePatient);
    }

    private Patient findPatient(final List<Patient> patients, final Patient unsavedPatient) {
        String unsavedGivenName = unsavedPatient.getGivenName();
        String unsavedFamilyName = unsavedPatient.getFamilyName();
        PersonName unsavedPersonName = unsavedPatient.getPersonName();
        for (Patient patient : patients) {
            // match it using the person name and gender, what about the dob?
            PersonName savedPersonName = patient.getPersonName();
            if (StringUtils.isNotBlank(savedPersonName.getFullName())
                    && StringUtils.isNotBlank(unsavedPersonName.getFullName())) {
                String savedGivenName = savedPersonName.getGivenName();
                int givenNameEditDistance = StringUtils.getLevenshteinDistance(
                        StringUtils.lowerCase(savedGivenName),
                        StringUtils.lowerCase(unsavedGivenName));
                String savedFamilyName = savedPersonName.getFamilyName();
                int familyNameEditDistance = StringUtils.getLevenshteinDistance(
                        StringUtils.lowerCase(savedFamilyName),
                        StringUtils.lowerCase(unsavedFamilyName));
                if (givenNameEditDistance < 3 && familyNameEditDistance < 3) {
                    if (StringUtils.equalsIgnoreCase(patient.getGender(), unsavedPatient.getGender())) {
                        if (patient.getBirthdate() != null && unsavedPatient.getBirthdate() != null
                                && DateUtils.isSameDay(patient.getBirthdate(), unsavedPatient.getBirthdate())) {
                            return patient;
                        }
                    }
                }
            }
        }
        return null;
    }

    private void processObs(final Encounter encounter, final Obs parentObs, final Object obsObject) throws QueueProcessorException {
        if (obsObject instanceof JSONObject) {
            JSONObject obsJsonObject = (JSONObject) obsObject;
            for (String conceptQuestion : obsJsonObject.keySet()) {
                String[] conceptElements = StringUtils.split(conceptQuestion, "\\^");
                if (conceptElements.length < 3)
                    continue;
                int conceptId = Integer.parseInt(conceptElements[0]);
                Concept concept = Context.getConceptService().getConcept(conceptId);
                if (concept.isSet()) {
                    Obs obsGroup = new Obs();
                    obsGroup.setConcept(concept);
                    obsGroup.setCreator(encounter.getCreator());
                    processObsObject(encounter, obsGroup, obsJsonObject.get(conceptQuestion));
                    encounter.addObs(obsGroup);
                    if (parentObs != null) {
                        parentObs.addGroupMember(obsGroup);
                    }
                } else {
                    Object valueObject = obsJsonObject.get(conceptQuestion);
                    String value = valueObject.toString();
                    Obs obs = new Obs();
                    obs.setConcept(concept);
                    obs.setEncounter(encounter);
                    obs.setPerson(encounter.getPatient());
                    obs.setObsDatetime(encounter.getEncounterDatetime());
                    obs.setLocation(encounter.getLocation());
                    obs.setCreator(encounter.getCreator());
                    // find the obs value :)
                    if (concept.getDatatype().isNumeric()) {
                        obs.setValueNumeric(Double.parseDouble(value));
                    } else if (concept.getDatatype().isDate()
                            || concept.getDatatype().isTime()
                            || concept.getDatatype().isDateTime()) {
                        obs.setValueDatetime(parseDate(value));
                    } else if (concept.getDatatype().isCoded()) {
                        String[] valueCodedElements = StringUtils.split(value, "\\^");
                        int valueCodedId = Integer.parseInt(valueCodedElements[0]);
                        Concept valueCoded = Context.getConceptService().getConcept(valueCodedId);
                        obs.setValueCoded(valueCoded);
                    } else if (concept.getDatatype().isText()) {
                        obs.setValueText(value);
                    }
                    // only add if the value is not empty :)
                    encounter.addObs(obs);
                    if (parentObs != null) {
                        parentObs.addGroupMember(obs);
                    }
                }
            }
        }
    }

    private void processObsObject(final Encounter encounter, final Obs parentObs, final Object childObsObject) {
        Object o = JsonUtils.readAsObject(childObsObject.toString(), "$");
        if (o instanceof JSONArray) {
            JSONArray jsonArray = (JSONArray) o;
            for (Object arrayElement : jsonArray) {
                processObs(encounter, parentObs, arrayElement);
            }
        } else if (o instanceof JSONObject) {
            processObs(encounter, parentObs, o);
        }
    }

    private void processEncounter(final Encounter encounter, final Object encounterObject) throws QueueProcessorException {
        String encounterPayload = encounterObject.toString();

        String formUuid = JsonUtils.readAsString(encounterPayload, "$['encounter']['form.uuid']");
        Form form = Context.getFormService().getFormByUuid(formUuid);
        encounter.setForm(form);

        String encounterTypeUuid = JsonUtils.readAsString(encounterPayload, "$['encounter']['encounterType.uuid']");
        EncounterType encounterType = Context.getEncounterService().getEncounterTypeByUuid(encounterTypeUuid);
        encounter.setEncounterType(encounterType);

        String providerUuid = JsonUtils.readAsString(encounterPayload, "$['encounter']['provider.uuid']");
        User user = Context.getUserService().getUserByUuid(providerUuid);
        encounter.setProvider(user);

        String locationUuid = JsonUtils.readAsString(encounterPayload, "$['encounter']['location.uuid']");
        Location location = Context.getLocationService().getLocationByUuid(locationUuid);
        encounter.setLocation(location);

        String encounterDatetime = JsonUtils.readAsString(encounterPayload, "$['encounter']['datetime']");
        encounter.setEncounterDatetime(parseDate(encounterDatetime));
    }

    private Date parseDate(final String dateValue) {
        Date date = null;
        try {
            date = dateFormat.parse(dateValue);
        } catch (ParseException e) {
            log.error("Unable to parse date data for encounter!", e);
        }
        return date;
    }

    @Override
    public boolean accept(final QueueData queueData) {
        return StringUtils.equals(DISCRIMINATOR_VALUE, queueData.getDiscriminator());
    }
}

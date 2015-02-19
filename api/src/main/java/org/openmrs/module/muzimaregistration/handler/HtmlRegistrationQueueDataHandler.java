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
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonName;
import org.openmrs.annotation.Handler;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.muzima.exception.QueueProcessorException;
import org.openmrs.module.muzima.model.QueueData;
import org.openmrs.module.muzima.model.handler.QueueDataHandler;
import org.openmrs.module.muzimaregistration.api.RegistrationDataService;
import org.openmrs.module.muzimaregistration.api.model.RegistrationData;
import org.openmrs.module.muzimaregistration.utils.JsonUtils;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * TODO: Write brief description about the class here.
 */
@Handler(supports = QueueData.class, order = 1)
public class HtmlRegistrationQueueDataHandler implements QueueDataHandler {

    private static final String DISCRIMINATOR_VALUE = "html-registration";

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    private final Log log = LogFactory.getLog(HtmlRegistrationQueueDataHandler.class);

    private final Patient unsavedPatient = new Patient();

    private PatientService patientService;


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

        createPatientFromPayload(payload);
        RegistrationDataService registrationDataService = Context.getService(RegistrationDataService.class);

        RegistrationData registrationData;
        if (StringUtils.isNotEmpty(unsavedPatient.getUuid())) {
            registrationData = registrationDataService.getRegistrationDataByTemporaryUuid(unsavedPatient.getUuid());
            if (registrationData == null) {
                // we can't find registration data for this uuid, process the registration form.

                Patient savedPatient = null;
                // check whether we already have similar patients!
                if (unsavedPatient.getNames().isEmpty()) {
                    PatientIdentifier identifier = unsavedPatient.getPatientIdentifier();
                    if (identifier != null) {
                        List<Patient> patients = patientService.getPatients(identifier.getIdentifier());
                        savedPatient = findPatient(patients, unsavedPatient);
                    }
                } else {
                    PersonName personName = unsavedPatient.getPersonName();
                    List<Patient> patients = patientService.getPatients(personName.getFullName());
                    savedPatient = findPatient(patients, unsavedPatient);
                }

                registrationData = new RegistrationData();
                registrationData.setTemporaryUuid(unsavedPatient.getUuid());
                String assignedUuid="";
                // for a new patient we will create mapping:
                // * temporary uuid --> uuid of the newly created patient
                // for existing patient we will create mapping:
                // * temporary uuid --> uuid of the existing patient
                if (savedPatient != null) {
                    // if we have a patient already saved with the characteristic found in the registration form:
                } else {
                    patientService.savePatient(unsavedPatient);
                    // * we will map the temporary uuid to the existing uuid.
                    //assignedUuid = savedPatient.getUuid();
                    assignedUuid = unsavedPatient.getUuid();
                }
                registrationData.setAssignedUuid(assignedUuid);
                registrationDataService.saveRegistrationData(registrationData);
            }
        }
    }

    private void createPatientFromPayload(final String payload) {
        String uuid = JsonUtils.readAsString(payload, "$['patient.uuid']");
        unsavedPatient.setUuid(uuid);

        setPatientIdentifiers(payload);

        Date birthDate = JsonUtils.readAsDate(payload, "$['patient.birthdate']");
        setPatientBirthDate(birthDate);

        boolean birthdateEstimated = JsonUtils.readAsBoolean(payload, "$['patient.birthdate_estimated']");
        setPatientBirthDateEstimated(birthdateEstimated);

        String gender = JsonUtils.readAsString(payload, "$['patient.sex']");
        setPatientGender(gender);

        String givenName = JsonUtils.readAsString(payload, "$['patient.given_name']");
        String middleName = JsonUtils.readAsString(payload, "$['patient.middle_name']");
        String familyName = JsonUtils.readAsString(payload, "$['patient.family_name']");
        addPatientName(givenName, middleName, familyName);
    }

    private void setPatientIdentifiers(String payload){
        Set<PatientIdentifier> patientIdentifiers = new HashSet<PatientIdentifier>();

        patientIdentifiers.add(getPreferredPatientIdentifier(payload));

        List<PatientIdentifier> otherIdentifiers = getOtherPatientIdentifiers(payload);
        if(!otherIdentifiers.isEmpty())
            patientIdentifiers.addAll(otherIdentifiers);

        String locationId = JsonUtils.readAsString(payload, "$['encounter.location_id']");
        setIdentifierTypeLocation(patientIdentifiers,locationId);
        unsavedPatient.setIdentifiers(patientIdentifiers);
    }

    private PatientIdentifier getPreferredPatientIdentifier(String patientPayload){
        String identifierValue = JsonUtils.readAsString(patientPayload, "patient.medical_record_number");
        String identifierTypeName = "AMRS Universal ID";

        PatientIdentifier preferredPatientIdentifier = createPatientIdentifier(identifierTypeName, identifierValue);
        preferredPatientIdentifier.setPreferred(true);

        return preferredPatientIdentifier;
    }
    private List<PatientIdentifier> getOtherPatientIdentifiers(String patientPayload){
        List<PatientIdentifier> otherIdentifiers = new ArrayList<PatientIdentifier>();
        Object identifierTypeNameObject = JsonUtils.readAsObject(patientPayload,"other_identifier_type");
        Object identifierValueObject =JsonUtils.readAsObject(patientPayload,"other_identifier_value");

        if(identifierTypeNameObject instanceof JSONArray) {
            JSONArray identifierTypeName = (JSONArray)identifierTypeNameObject;
            JSONArray identifierValue = (JSONArray)identifierValueObject;
            for (int i = 0; i < identifierTypeName.size(); i++) {
                PatientIdentifier identifier = createPatientIdentifier(identifierTypeName.get(i).toString(), identifierValue.get(i).toString());
                otherIdentifiers.add(identifier);
            }
            return otherIdentifiers;
        }else if(identifierTypeNameObject instanceof String){
            String identifierTypeName = (String)identifierTypeNameObject;
            String identifierValue = (String)identifierValueObject;
            PatientIdentifier identifier = createPatientIdentifier(identifierTypeName, identifierValue);
            otherIdentifiers.add(identifier);
        }
        return otherIdentifiers;
    }
    private PatientIdentifier createPatientIdentifier(String identifierTypeName,String identifierValue) {
        PatientIdentifierType identifierType = Context.getPatientService().getPatientIdentifierTypeByName(identifierTypeName);
        if (identifierType != null) {
            PatientIdentifier patientIdentifier = new PatientIdentifier();
            patientIdentifier.setIdentifierType(identifierType);
            patientIdentifier.setIdentifier(identifierValue);
            return patientIdentifier;
        }else{
            throw new QueueProcessorException("Unable to find identifier type with name: " + identifierTypeName);
        }
    }

    private void setIdentifierTypeLocation(final Set<PatientIdentifier> patientIdentifiers, String locationId){
        Location location = Context.getLocationService().getLocation(locationId);
        if (location == null) {
            throw new QueueProcessorException("Unable to find encounter location using the id: " + locationId);
        }
        Iterator<PatientIdentifier> iterator = patientIdentifiers.iterator();
        while(iterator.hasNext()) {
            PatientIdentifier identifier = iterator.next();
            identifier.setLocation(location);
        }
    }

    private void setPatientBirthDate(Date birthDate){
        unsavedPatient.setBirthdate(birthDate);
    }

    private void setPatientBirthDateEstimated(boolean birthdateEstimated){
        unsavedPatient.setBirthdateEstimated(birthdateEstimated);
    }

    private void setPatientGender(String gender){
        unsavedPatient.setGender(gender);
    }

    private void addPatientName(String givenName,String middleName,String familyName){
        PersonName personName = new PersonName();
        personName.setGivenName(givenName);
        personName.setMiddleName(middleName);
        personName.setFamilyName(familyName);

        unsavedPatient.addName(personName);
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
                            && DateUtils.isSameDay(patient.getBirthdate(), unsavedPatient.getBirthdate())) {
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
    @Override
    public boolean accept(final QueueData queueData) {
        return StringUtils.equals(DISCRIMINATOR_VALUE, queueData.getDiscriminator());
    }
}

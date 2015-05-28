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

    private Patient unsavedPatient;
    private String payload;
    private QueueProcessorException queueProcessorException;

    @Override
    public void process(final QueueData queueData) throws QueueProcessorException {
        log.info("Processing registration form data: " + queueData.getUuid());
        queueProcessorException = new QueueProcessorException();
        try {
            if (validate(queueData)) {
                RegisterUnsavedPatient();
            }
        }
        catch (Exception e){
            if(!e.getClass().equals(QueueProcessorException.class)) {
                queueProcessorException.addException(e);
            }
        }
        finally {
            if (queueProcessorException.anyExceptions()) {
                throw queueProcessorException;
            }
        }
    }

    @Override
    public boolean validate(QueueData queueData) {
        log.info("Processing registration form data: " + queueData.getUuid());
        queueProcessorException = new QueueProcessorException();
        try {
            payload = queueData.getPayload();
            unsavedPatient = new Patient();
            populateUnsavedPatientFromPayload();
            validateUnsavedPatient();
            return true;
        }
        catch (Exception e){
            queueProcessorException.addException(e);
            return false;
        }
        finally {
            if (queueProcessorException.anyExceptions()) {
                throw queueProcessorException;
            }
        }
    }

    private void validateUnsavedPatient() {
        Patient savedPatient = findSimilarSavedPatient();
        if (savedPatient != null) {
            queueProcessorException.addException(new Exception("Found a patient with similar characteristic :  patientId =" + savedPatient.getPatientId()
                    + "Identifier Id = "+ savedPatient.getPatientIdentifier().getIdentifier()));
        }
    }


    private void populateUnsavedPatientFromPayload() {
        setPatientIdentifiersFromPayload();
        setPatientBirthDateFromPayload();
        setPatientBirthDateEstimatedFromPayload();
        setPatientGenderFromPayload();
        setPatientNameFromPayload();
    }

    private void setPatientIdentifiersFromPayload(){
        Set<PatientIdentifier> patientIdentifiers = new HashSet<PatientIdentifier>();
        PatientIdentifier preferredIdentifier = getPreferredPatientIdentifierFromPayload();
        if(preferredIdentifier!=null) {
            patientIdentifiers.add(preferredIdentifier);
        }
        List<PatientIdentifier> otherIdentifiers = getOtherPatientIdentifiersFromPayload();
        if(!otherIdentifiers.isEmpty()) {
            patientIdentifiers.addAll(otherIdentifiers);
        }
        setIdentifierTypeLocation(patientIdentifiers);

        unsavedPatient.setIdentifiers(patientIdentifiers);
    }

    private PatientIdentifier getPreferredPatientIdentifierFromPayload(){
        String identifierValue = JsonUtils.readAsString(payload, "$.patient.['patient.medical_record_number']");
        String identifierTypeName = "AMRS Universal ID";

        PatientIdentifier preferredPatientIdentifier = createPatientIdentifier(identifierTypeName, identifierValue);
        if(preferredPatientIdentifier!=null)
        {   preferredPatientIdentifier.setPreferred(true);
            return preferredPatientIdentifier;
        }
        else {
            return null;
        }
    }
    private List<PatientIdentifier> getOtherPatientIdentifiersFromPayload(){
        List<PatientIdentifier> otherIdentifiers = new ArrayList<PatientIdentifier>();
        Object identifierTypeNameObject = JsonUtils.readAsObject(payload,"$.observation.other_identifier_type");
        Object identifierValueObject =JsonUtils.readAsObject(payload,"$.observation.other_identifier_value");

        if(identifierTypeNameObject instanceof JSONArray) {
            JSONArray identifierTypeName = (JSONArray)identifierTypeNameObject;
            JSONArray identifierValue = (JSONArray)identifierValueObject;
            for (int i = 0; i < identifierTypeName.size(); i++) {
                PatientIdentifier identifier = createPatientIdentifier(identifierTypeName.get(i).toString(), identifierValue.get(i).toString());
                if(identifier != null) {
                    otherIdentifiers.add(identifier);
                }
            }
        }else if(identifierTypeNameObject instanceof String){
            String identifierTypeName = (String)identifierTypeNameObject;
            String identifierValue = (String)identifierValueObject;
            PatientIdentifier identifier = createPatientIdentifier(identifierTypeName, identifierValue);
            if(identifier != null) {
                otherIdentifiers.add(identifier);
            }
        }
        return otherIdentifiers;
    }
    private PatientIdentifier createPatientIdentifier(String identifierTypeName,String identifierValue) {
        PatientIdentifierType identifierType = Context.getPatientService().getPatientIdentifierTypeByName(identifierTypeName);
        if (identifierType == null) {
            queueProcessorException.addException(new Exception("Unable to find identifier type with name: " + identifierTypeName));
        }
        else if (identifierValue==null) {
            queueProcessorException.addException(new Exception("Identifier value can't be null" + identifierTypeName));
        }
        else {
            PatientIdentifier patientIdentifier = new PatientIdentifier();
            patientIdentifier.setIdentifierType(identifierType);
            patientIdentifier.setIdentifier(identifierValue);
            return patientIdentifier;
        }
        return null;
    }

    private void setIdentifierTypeLocation(final Set<PatientIdentifier> patientIdentifiers){
        String locationIdString = JsonUtils.readAsString(payload, "$.encounter.['encounter.location_id']");
        int locationId=Integer.parseInt(locationIdString);
        Location location = Context.getLocationService().getLocation(locationId);
        if (location == null) {
            queueProcessorException.addException(new Exception("Unable to find encounter location using the id: " + locationId));
        }else {
            Iterator<PatientIdentifier> iterator = patientIdentifiers.iterator();
            while (iterator.hasNext()) {
                PatientIdentifier identifier = iterator.next();
                identifier.setLocation(location);
            }
        }
    }

    private void setPatientBirthDateFromPayload(){
        Date birthDate = JsonUtils.readAsDate(payload, "$.patient.['patient.birth_date']");
        unsavedPatient.setBirthdate(birthDate);
    }

    private void setPatientBirthDateEstimatedFromPayload(){
        boolean birthdateEstimated = JsonUtils.readAsBoolean(payload, "$.patient.['patient.birthdate_estimated']");
        unsavedPatient.setBirthdateEstimated(birthdateEstimated);
    }

    private void setPatientGenderFromPayload(){
        String gender = JsonUtils.readAsString(payload, "$.patient.['patient.sex']");
        unsavedPatient.setGender(gender);
    }

    private void setPatientNameFromPayload(){
        String givenName = JsonUtils.readAsString(payload, "$.patient.['patient.given_name']");
        String familyName = JsonUtils.readAsString(payload, "$.patient.['patient.family_name']");
        String middleName="";
        try{
            middleName= JsonUtils.readAsString(payload, "$.patient.['patient.middle_name']");
        }catch(Exception e){}

        PersonName personName = new PersonName();
        personName.setGivenName(givenName);
        personName.setMiddleName(middleName);
        personName.setFamilyName(familyName);

        unsavedPatient.addName(personName);
    }

    private void RegisterUnsavedPatient(){
        RegistrationDataService registrationDataService = Context.getService(RegistrationDataService.class);
        String temporaryUuid = getPatientUuidFromPayload();
        RegistrationData registrationData = registrationDataService.getRegistrationDataByTemporaryUuid(temporaryUuid);
        if (registrationData == null) {
            registrationData = new RegistrationData();
            registrationData.setTemporaryUuid(temporaryUuid);
            Context.getPatientService().savePatient(unsavedPatient);
            String assignedUuid = unsavedPatient.getUuid();
            registrationData.setAssignedUuid(assignedUuid);
            registrationDataService.saveRegistrationData(registrationData);
        }
    }

    private String getPatientUuidFromPayload(){
        return JsonUtils.readAsString(payload, "$.patient.['patient.uuid']");
    }

    private Patient findSimilarSavedPatient(){
        Patient savedPatient = null;
        if (unsavedPatient.getNames().isEmpty()) {
            PatientIdentifier identifier = unsavedPatient.getPatientIdentifier();
            if (identifier != null) {
                List<Patient> patients = Context.getPatientService().getPatients(identifier.getIdentifier());
                savedPatient = findPatient(patients, unsavedPatient);
            }
        } else {
            PersonName personName = unsavedPatient.getPersonName();
            List<Patient> patients = Context.getPatientService().getPatients(personName.getFullName());
            savedPatient = findPatient(patients, unsavedPatient);
        }
        return savedPatient;
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

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
import org.openmrs.api.LocationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.muzima.exception.QueueProcessorException;
import org.openmrs.module.muzima.model.QueueData;
import org.openmrs.module.muzima.model.handler.QueueDataHandler;
import org.openmrs.module.muzimaregistration.api.RegistrationDataService;
import org.openmrs.module.muzimaregistration.api.model.RegistrationData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

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

        Patient unsavedPatient = createPatientFromPayload(payload);
        RegistrationDataService registrationDataService = Context.getService(RegistrationDataService.class);

        RegistrationData registrationData;
        if (StringUtils.isNotEmpty(unsavedPatient.getUuid())) {
            registrationData = registrationDataService.getRegistrationDataByTemporaryUuid(unsavedPatient.getUuid());
            if (registrationData == null) {
                // we can't find registration data for this uuid, process the registration form.
                patientService = Context.getPatientService();
                locationService = Context.getLocationService();

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
                registrationData.setTemporaryUuid(unsavedPatient.getUuid());
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

    private Date parseDate(final String dateValue) {
        Date date = null;
        try {
            date = dateFormat.parse(dateValue);
        } catch (ParseException e) {
            log.error("Unable to parse date data for encounter!", e);
        }
        return date;
    }

    private Patient createPatientFromPayload(final String payload) {
        Patient unsavedPatient = new Patient();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.parse(new InputSource(new ByteArrayInputStream(payload.getBytes("utf-8"))));

            Element element = document.getDocumentElement();
            element.normalize();

            Node patientNode = document.getElementsByTagName("patient").item(0);
            NodeList patientElementNodes = patientNode.getChildNodes();

            PersonName personName = new PersonName();
            PatientIdentifier patientIdentifier = new PatientIdentifier();
            for (int i = 0; i < patientElementNodes.getLength(); i++) {
                Node patientElementNode = patientElementNodes.item(i);
                if (patientElementNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element patientElement = (Element) patientElementNode;
                    if (patientElement.getTagName().equals("patient.middle_name")) {
                        personName.setMiddleName(patientElement.getTextContent());
                    } else if (patientElement.getTagName().equals("patient.given_name")) {
                        personName.setGivenName(patientElement.getTextContent());
                    } else if (patientElement.getTagName().equals("patient.family_name")) {
                        personName.setFamilyName(patientElement.getTextContent());
                    } else if (patientElement.getTagName().equals("patient_identifier.identifier_type_id")) {
                        int identifierTypeId = Integer.parseInt(patientElement.getTextContent());
                        PatientIdentifierType identifierType = Context.getPatientService().getPatientIdentifierType(identifierTypeId);
                        patientIdentifier.setIdentifierType(identifierType);
                    } else if (patientElement.getTagName().equals("patient.medical_record_number")) {
                        patientIdentifier.setIdentifier(patientElement.getTextContent());
                    } else if (patientElement.getTagName().equals("patient.sex")) {
                        unsavedPatient.setGender(patientElement.getTextContent());
                    } else if (patientElement.getTagName().equals("patient.birthdate")) {
                        Date dob = parseDate(patientElement.getTextContent());
                        unsavedPatient.setBirthdate(dob);
                    } else if (patientElement.getTagName().equals("patient.uuid")) {
                        unsavedPatient.setUuid(patientElement.getTextContent());
                    }
                }
            }

            Node encounterNode = document.getElementsByTagName("encounter").item(0);
            NodeList encounterElementNodes = encounterNode.getChildNodes();

            for (int i = 0; i < encounterElementNodes.getLength(); i++) {
                Node encounterElementNode = encounterElementNodes.item(i);
                if (encounterElementNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element encounterElement = (Element) encounterElementNode;
                    if (encounterElement.getTagName().equals("encounter.location_id")) {
                        int locationId = Integer.parseInt(encounterElement.getTextContent());
                        Location location = Context.getLocationService().getLocation(locationId);
                        patientIdentifier.setLocation(location);
                    }
                }
            }

            unsavedPatient.addName(personName);
            unsavedPatient.addIdentifier(patientIdentifier);
        } catch (ParserConfigurationException e) {
            throw new QueueProcessorException(e);
        } catch (SAXException e) {
            throw new QueueProcessorException(e);
        } catch (IOException e) {
            throw new QueueProcessorException(e);
        }
        return unsavedPatient;
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

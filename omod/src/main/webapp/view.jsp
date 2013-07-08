<%@ include file="/WEB-INF/template/include.jsp" %>
<%@ include file="/WEB-INF/template/header.jsp" %>
<openmrs:htmlInclude file="/moduleResources/muzimaregistration/styles/custom/custom.css"/>
<openmrs:htmlInclude file="/moduleResources/muzimaregistration/styles/bootstrap/css/bootstrap.css"/>

<openmrs:htmlInclude file="/moduleResources/muzimaregistration/js/jquery/jquery.js" />

<openmrs:htmlInclude file="/moduleResources/muzimaregistration/js/angular/angular.js"/>
<openmrs:htmlInclude file="/moduleResources/muzimaregistration/js/angular/angular-resource.js"/>
<openmrs:htmlInclude file="/moduleResources/muzimaregistration/js/custom/app.js"/>
<openmrs:htmlInclude file="/moduleResources/muzimaregistration/js/custom/controller.js"/>

<openmrs:htmlInclude file="/moduleResources/muzimaregistration/js/ui-bootstrap/ui-bootstrap-custom-0.4.0.js"/>
<openmrs:htmlInclude file="/moduleResources/muzimaregistration/js/ui-bootstrap/ui-bootstrap-custom-tpls-0.4.0.js"/>

<h3><spring:message code="muzimaregistration.view"/></h3>
<div class="bootstrap-scope" ng-app="muzimaregistration">
    <div ng-view ></div>
</div>

<%@ include file="/WEB-INF/template/footer.jsp" %>


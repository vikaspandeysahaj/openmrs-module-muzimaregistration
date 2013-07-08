var muzimaregistration = angular.module('muzimaregistration', ['ui.bootstrap']);

muzimaregistration.
    config(['$routeProvider', '$compileProvider', function ($routeProvider, $compileProvider) {
        $compileProvider.urlSanitizationWhitelist(/^\s*(https?|ftp|mailto|file):/);
        $routeProvider.when('/registrations', {controller: ListRegistrationsCtrl,
            templateUrl: '../../moduleResources/muzimaregistration/partials/registrations.html'});
        $routeProvider.when('/registration/:uuid', {controller: ViewRegistrationCtrl,
            templateUrl: '../../moduleResources/muzimaregistration/partials/registration.html'});
        $routeProvider.otherwise({redirectTo: '/registrations'});
    }]);

muzimaregistration.factory('$registrations', function($http) {
    var getRegistration = function(uuid) {
        return $http.get("registration.json?uuid=" + uuid);
    };
    var getRegistrations = function(pageNumber, pageSize) {
        return $http.get("registrations.json?pageNumber=" + pageNumber + "&pageSize=" + pageSize);
    };
    return {
        getRegistrations: getRegistrations,
        getRegistration: getRegistration
    }
});


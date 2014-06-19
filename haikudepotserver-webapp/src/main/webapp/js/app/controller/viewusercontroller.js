/*
 * Copyright 2013-2014, Andrew Lindesay
 * Distributed under the terms of the MIT License.
 */

angular.module('haikudepotserver').controller(
    'ViewUserController',
    [
        '$scope','$log','$location','$routeParams','$window',
        'jsonRpc','constants','errorHandling','messageSource','userState','breadcrumbs',
        function(
            $scope,$log,$location,$routeParams,$window,
            jsonRpc,constants,errorHandling,messageSource,userState,breadcrumbs) {

            $scope.breadcrumbItems = undefined;
            $scope.user = undefined;

            $scope.shouldSpin = function() {
                return undefined == $scope.user;
            };

            refreshUser();

            function refreshBreadcrumbItems() {
                breadcrumbs.mergeCompleteStack([
                    breadcrumbs.createHome(),
                    breadcrumbs.applyCurrentLocation(breadcrumbs.createViewUser($scope.user))
                ]);
            }

            function refreshUser() {
                jsonRpc.call(
                        constants.ENDPOINT_API_V1_USER,
                        "getUser",
                        [{
                            nickname : $routeParams.nickname
                        }]
                    ).then(
                    function(result) {
                        $scope.user = result;
                        refreshBreadcrumbItems();
                        $log.info('fetched user; '+result.nickname);
                    },
                    function(err) {
                        errorHandling.handleJsonRpcError(err);
                    }
                );
            };

            $scope.canLogout = function() {
                return userState.user() &&
                    $scope.user &&
                    userState.user().nickname == $scope.user.nickname;
            }

            $scope.goChangePassword = function() {
                breadcrumbs.pushAndNavigate(breadcrumbs.createChangePassword($scope.user));
            }

            $scope.goEdit = function() {
                breadcrumbs.pushAndNavigate(breadcrumbs.createEditUser($scope.user));
            }

            $scope.goLogout = function() {
                userState.user(null);
                breadcrumbs.reset();
                $window.location.href = '/';
            }

        }
    ]
);
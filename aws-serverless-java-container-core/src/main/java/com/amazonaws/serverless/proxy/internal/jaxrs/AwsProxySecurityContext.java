/*
 * Copyright 2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.serverless.proxy.internal.jaxrs;

import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.CognitoAuthorizerClaims;
import com.amazonaws.services.lambda.runtime.Context;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

/**
 * default implementation of the <code>SecurityContext</code> object. This class supports 3 API Gateway's authorization methods:
 * AWS_IAM, CUSTOM_AUTHORIZER, and COGNITO_USER_POOL (oidc). The Principal returned by the object depends on the authorization
 * model:
 *  * AWS_IAM: User ARN
 *  * CUSTOM_AUTHORIZER: Custom user principal
 *  * COGNITO_USER_POOL: Cognito identity id
 */
public class AwsProxySecurityContext
        implements SecurityContext {

    //-------------------------------------------------------------
    // Constants - Private
    //-------------------------------------------------------------

    private static final String AUTH_SCHEME_CUSTOM = "CUSTOM_AUTHORIZER";
    private static final String AUTH_SCHEME_COGNITO_POOL = "COGNITO_USER_POOL";
    private static final String AUTH_SCHEME_AWS_IAM = "AWS_IAM";


    //-------------------------------------------------------------
    // Variables - Private
    //-------------------------------------------------------------

    protected Context lambdaContext;
    protected AwsProxyRequest event;


    //-------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------

    public AwsProxySecurityContext(final Context lambdaContext, final AwsProxyRequest event) {
        this.lambdaContext = lambdaContext;
        this.event = event;
    }


    //-------------------------------------------------------------
    // Implementation - SecurityContext
    //-------------------------------------------------------------

    public Principal getUserPrincipal() {
        if (getAuthenticationScheme() == null) {
            return () -> null;
        }

        if (getAuthenticationScheme().equals(AUTH_SCHEME_CUSTOM) || getAuthenticationScheme().equals(AUTH_SCHEME_AWS_IAM)) {
            return () -> {
                if (getAuthenticationScheme().equals(AUTH_SCHEME_CUSTOM)) {
                    return event.getRequestContext().getAuthorizer().getPrincipalId();
                } else if (getAuthenticationScheme().equals(AUTH_SCHEME_AWS_IAM)) {
                    // if we received credentials from Cognito Federated Identities then we return the identity id
                    if (event.getRequestContext().getIdentity().getCognitoIdentityId() != null) {
                        return event.getRequestContext().getIdentity().getCognitoIdentityId();
                    } else { // otherwise the user arn from the credentials
                        return event.getRequestContext().getIdentity().getUserArn();
                    }
                }

                // return null if we couldn't find a valid scheme
                return null;
            };
        }

        if (getAuthenticationScheme().equals(AUTH_SCHEME_COGNITO_POOL)) {
            return new CognitoUserPoolPrincipal(event.getRequestContext().getAuthorizer().getClaims());
        }

        throw new RuntimeException("Cannot recognize authorization scheme in event");
    }


    public boolean isUserInRole(String role) {
        return (role.equals(event.getRequestContext().getIdentity().getUserArn()));
    }


    public boolean isSecure() {
        return getAuthenticationScheme() != null;
    }


    public String getAuthenticationScheme() {
        if (event.getRequestContext().getAuthorizer() != null && event.getRequestContext().getAuthorizer().getClaims() != null && event.getRequestContext().getAuthorizer().getClaims().getSubject() != null) {
            return AUTH_SCHEME_COGNITO_POOL;
        } else if (event.getRequestContext().getAuthorizer() != null) {
            return AUTH_SCHEME_CUSTOM;
        } else if (event.getRequestContext().getIdentity().getAccessKey() != null) {
            return AUTH_SCHEME_AWS_IAM;
        } else {
            return null;
        }
    }


    /**
     * Custom object for request authorized with a Cognito User Pool authorizer. By casting the Principal
     * object to this you can extract the Claims object included in the token.
     */
    public class CognitoUserPoolPrincipal implements Principal {

        private CognitoAuthorizerClaims claims;

        CognitoUserPoolPrincipal(CognitoAuthorizerClaims c) {
            claims = c;
        }

        @Override
        public String getName() {
            return claims.getSubject();
        }

        public CognitoAuthorizerClaims getClaims() {
            return claims;
        }
    }
}

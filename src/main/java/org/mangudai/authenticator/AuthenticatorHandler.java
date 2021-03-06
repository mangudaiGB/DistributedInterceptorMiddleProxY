/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.authenticator;

/**
 * Created by neo on 21/03/17.
 */
public interface AuthenticatorHandler {
    boolean authenticate(String userName, String password);
    String getRealm();
}

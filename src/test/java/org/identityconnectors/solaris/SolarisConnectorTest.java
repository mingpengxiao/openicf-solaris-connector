/*
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2008-2009 Sun Microsystems, Inc. All rights reserved.     
 * 
 * The contents of this file are subject to the terms of the Common Development 
 * and Distribution License("CDDL") (the "License").  You may not use this file 
 * except in compliance with the License.
 * 
 * You can obtain a copy of the License at 
 * http://IdentityConnectors.dev.java.net/legal/license.txt
 * See the License for the specific language governing permissions and limitations 
 * under the License. 
 * 
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at identityconnectors/legal/license.txt.
 * If applicable, add the following below this CDDL Header, with the fields 
 * enclosed by brackets [] replaced by your own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */
package org.identityconnectors.solaris;

import java.util.List;
import java.util.Set;

import junit.framework.Assert;

import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.FilterBuilder;
import org.identityconnectors.solaris.attr.AccountAttribute;
import org.identityconnectors.solaris.test.SolarisTestBase;
import org.identityconnectors.test.common.ToListResultsHandler;
import org.junit.Test;


public class SolarisConnectorTest extends SolarisTestBase {
    @Test
    public void testBasicTest() {
        String username = getUsername();
        // positive test update shell:
        Attribute expectedAttribute = AttributeBuilder.build(AccountAttribute.SHELL.getName(), "/bin/sh");
        Set<Attribute> replaceAttrs = CollectionUtil.newSet(expectedAttribute);
        getFacade().update(ObjectClass.ACCOUNT, new Uid(getUsername()), replaceAttrs, null);
        Assert.assertTrue(checkUser(username, expectedAttribute));
        
        // negative test update shell:
        expectedAttribute = AttributeBuilder.build(AccountAttribute.SHELL.getName(), "/nonsense/shell");
        replaceAttrs = CollectionUtil.newSet(expectedAttribute);
        try {
            getFacade().update(ObjectClass.ACCOUNT, new Uid(getUsername()), replaceAttrs, null);
            Assert.fail("Using bad shell value did not fail.");
        } catch (Exception ex) {
            // OK
        }
        
        // negative test: primary group
        expectedAttribute = AttributeBuilder.build(AccountAttribute.GROUP.getName(), "nonsensegroup");
        replaceAttrs = CollectionUtil.newSet(expectedAttribute);
        try {
            getFacade().update(ObjectClass.ACCOUNT, new Uid(getUsername()), replaceAttrs, null);
            Assert.fail("Changing the primary group to an invalid value did not fail.");
        } catch (Exception ex) {
            // OK
        }
        
        // negative test: secondary group
        if (!getConnection().isNis()) { // not applicable for NIS resources
            replaceAttrs = CollectionUtil.newSet(
                    AttributeBuilder.build(AccountAttribute.GROUP.getName() /* value is null */), 
                    AttributeBuilder.build(AccountAttribute.SECONDARY_GROUP.getName(), "nonsensegroup"));
            try {
                getFacade().update(ObjectClass.ACCOUNT, new Uid(getUsername()), replaceAttrs, null);
                Assert.fail("Changing the secondary group to an invalid value did not fail.");
            } catch (Exception ex) {
                // OK
            }
        }
        
         
    }

    /**
     * check if the user has the given attribute set to the given value.
     * @param username the accountid to check
     * @param expectedAttribute sources of attribute name/value to check
     * @return true if the attribute is set, false otherwise.
     */
    private boolean checkUser(String username, Attribute expectedAttribute) {
        ToListResultsHandler handler = new ToListResultsHandler();
        getFacade().search(ObjectClass.ACCOUNT, 
                FilterBuilder.equalTo(AttributeBuilder.build(Name.NAME, username)), 
                handler, 
                new OperationOptionsBuilder().setAttributesToGet(expectedAttribute.getName()).build()
                );
        
        List<ConnectorObject> l = handler.getObjects();
        Assert.assertTrue("the requested attribute is missing", l.size() == 1);
        ConnectorObject co = l.get(0);
        Attribute attr = co.getAttributeByName(expectedAttribute.getName());
        return CollectionUtil.equals(attr.getValue(), expectedAttribute.getValue());
    }

    @Override
    public boolean createGroup() {
        return false;
    }

    @Override
    public int getCreateUsersNumber() {
        return 1;
    }
    
    private String getUsername() {
        return getUsername(0);
    }
}
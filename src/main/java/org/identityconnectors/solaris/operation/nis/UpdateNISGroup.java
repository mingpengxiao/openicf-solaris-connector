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
 * http://opensource.org/licenses/cddl1.php
 * See the License for the specific language governing permissions and limitations
 * under the License.
 *
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at http://opensource.org/licenses/cddl1.php.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */
package org.identityconnectors.solaris.operation.nis;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.solaris.SolarisConnection;
import org.identityconnectors.solaris.attr.NativeAttribute;
import org.identityconnectors.solaris.operation.search.SolarisEntry;

public class UpdateNISGroup extends AbstractNISOp {
    public static void updateGroup(SolarisEntry group, SolarisConnection conn) {
        conn.doSudoStart();
        try {
            updateGroupImpl(group, conn);
        } finally {
            conn.doSudoReset();
        }
    }

    private static void updateGroupImpl(SolarisEntry group, SolarisConnection conn) {
        conn.executeCommand(AbstractNISOp.WHO_I_AM);

        conn.executeMutexAcquireScript(GRP_MUTEX_FILE, TMP_GRP_MUTEX_FILE, GRP_PID_FILE);
        try {
            impl(group, conn);
        } finally {
            conn.executeMutexReleaseScript(GRP_MUTEX_FILE);
        }
    }

    private static void impl(SolarisEntry group, SolarisConnection conn) {
        final String pwddir = conn.getConfiguration().getNisPwdDir();

        final String groupFile = pwddir + "/group";
        final String groupName = group.getName();

        Map<String, Attribute> attrMap =
                new HashMap<String, Attribute>(AttributeUtil.toMap(group.getAttributeSet()));
        attrMap = CollectionUtil.asReadOnlyMap(attrMap);

        boolean usersModified = attrMap.containsKey(NativeAttribute.USERS.getName());
        Attribute usersAttr = attrMap.get(NativeAttribute.USERS.getName());
        List<Object> users =
                (usersModified && usersAttr != null) ? usersAttr.getValue() : Collections
                        .emptyList();
        users = CollectionUtil.asReadOnlyList(users);

        Attribute idAttr = attrMap.get(NativeAttribute.ID.getName());
        String gid = (idAttr != null) ? AttributeUtil.getStringValue(idAttr) : null;

        Attribute nameAttr = attrMap.get(NativeAttribute.NAME.getName());
        String newName = (nameAttr != null) ? AttributeUtil.getStringValue(nameAttr) : null;
        if (newName == null) {
            newName = groupName;
        }

        final String removeTmpFilesScript = getRemoveGroupTmpFiles(conn);
        final String getOwner = initGetOwner(groupFile);
        final String updateGroup = initUpdateGroup(groupName, newName, groupFile, conn);
        final String groupRecord = initGroupRecord(groupName, gid, usersModified, users);

        conn.executeCommand(getOwner);
        conn.executeCommand(groupRecord);
        conn.executeCommand(removeTmpFilesScript);

        final String updateGroupOutput = conn.executeCommand(updateGroup);
        parseNisOutputForErrors(updateGroupOutput);

        conn.executeCommand(removeTmpFilesScript);

        addNISMake("group", conn);
    }

    private static String initGroupRecord(String groupName, String gid, boolean usersModified,
            List<Object> users) {
        StringBuilder groupRecord = new StringBuilder();

        groupRecord.append("ENTRYTEXT=`ypmatch " + groupName + " group`; ");

        if (StringUtil.isBlank(gid)) {
            groupRecord.append("NEWGID=`echo $ENTRYTEXT | cut -d: -f3`; ");
            groupRecord.append("unset WS_GIDDUPLICATE; ");
        } else {
            groupRecord.append("NEWGID=" + gid + "; ");
            groupRecord.append("WS_GIDDUPLICATE=" + gid + "; ");
        }

        if (!usersModified) {
            groupRecord.append("NEWUSERS=`echo $ENTRYTEXT | cut -d: -f4`; ");
        } else {
            groupRecord.append("NEWUSERS=" + listToString(users, ",") + "; ");
        }

        return groupRecord.toString();
    }

    static String listToString(List<? extends Object> users, String separator) {
        StringBuilder result = new StringBuilder();
        Iterator<? extends Object> iter = users.iterator();
        while (iter.hasNext()) {
            Object user = iter.next();
            result.append(user.toString());
            if (iter.hasNext()) {
                result.append(separator);
            }
        }
        return result.toString();
    }

    private static String initUpdateGroup(String groupName, String newName, String groupFile,
            SolarisConnection conn) {
        String cpCmd = conn.buildCommand(false, "cp");
        String chownCmd = conn.buildCommand(true, "chown");
        String diffCmd = conn.buildCommand(false, "diff");
        String grepCmd = conn.buildCommand(false, "grep");
        String catCmd = conn.buildCommand(false, "cat");

        // @formatter:off
        String updateGroup =
            "if [ -n \"$ENTRYTEXT\" ]; then\n" +
              "if [ -n \"$WS_GIDDUPLICATE\" ]; then " +
                "WS_GROUPID=`" + catCmd + groupFile + " | cut -d: -f3 | grep $WS_GIDDUPLICATE`; " +
                "if [ -n \"$WS_GROUPID\" ]; then\n" +
                  "echo \"" + DUPLICATE_GROUP_ID_MSG + "\"; " +
                "else " +
                  "unset WS_GIDDUPLICATE; " +
                "fi; " +
              "fi; " +
              "if [ -z \"$WS_GIDDUPLICATE\" ]; then\n" +
                "PASSWD=`grep \"^" + groupName + ":\" " + groupFile + " | cut -d: -f2`; " +
                cpCmd + "-p " + groupFile + " " + TMP_GROUPFILE_1 + "; " +
                grepCmd + "-v \"^" + groupName + ":\" " + TMP_GROUPFILE_1 + " > " + TMP_GROUPFILE_2 + "; " +
                chownCmd + "$WHOIAM " + TMP_GROUPFILE_2 + "\n " +
                "echo \"" + newName + ":$PASSWD:$NEWGID:$NEWUSERS\" >> " + TMP_GROUPFILE_2 + "; " +
                diffCmd + groupFile + " " + TMP_GROUPFILE_1 + " 2>&1 >/dev/null; " +
                "RC=$?; " +
                "if [ $RC -eq 0 ]; then\n" +
                  cpCmd + "-f " + TMP_GROUPFILE_2 + " " + groupFile + "; " +
                  chownCmd + "$OWNER:$GOWNER " + groupFile + "; " +
                "else " +
                  "echo \"Error modifying " + groupFile + " for entry " + groupName + ".\"; " +
                "fi;\n" +
              "fi; " +
            "else " +
              "echo \"" + groupName + " not found in " + groupFile + ".\"; " +
            "fi";
        // @formatter:off
        return updateGroup;
    }
}

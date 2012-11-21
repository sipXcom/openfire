/**
 * $RCSfile$
 * $Revision: 3127 $
 * $Date: 2005-11-30 15:26:07 -0300 (Wed, 30 Nov 2005) $
 *
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.group;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.event.GroupEventDispatcher;
import org.jivesoftware.openfire.provider.GroupPropertiesProvider;
import org.jivesoftware.openfire.provider.GroupProvider;
import org.jivesoftware.openfire.provider.ProviderFactory;
import org.jivesoftware.util.cache.CacheSizes;
import org.jivesoftware.util.cache.Cacheable;
import org.jivesoftware.util.cache.ExternalizableUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

/**
 * Groups organize users into a single entity for easier management.<p>
 *
 * The actual group implementation is controlled by the {@link GroupProvider}, which
 * includes things like the group name, the members, and adminstrators. Each group
 * also has properties, which are always stored in the Openfire database.
 *
 * @see GroupManager#createGroup(String)
 *
 * @author Matt Tucker
 */
public class Group implements Cacheable, Externalizable {

	private static final Logger Log = LoggerFactory.getLogger(Group.class);

    private transient GroupProvider provider;
    private transient GroupManager groupManager;

    private String name;
    private String description;
    private Map<String, String> properties;
    private Set<JID> members;
    private Set<JID> administrators;

    /**
     * Provider for underlying storage
     */
    private final GroupPropertiesProvider propsProvider = ProviderFactory.getGroupPropertiesProvider();

    /**
     * Constructor added for Externalizable. Do not use this constructor.
     */
    public Group() {
    }

    /**
     * Constructs a new group. Note: this constructor is intended for implementors of the
     * {@link GroupProvider} interface. To create a new group, use the
     * {@link GroupManager#createGroup(String)} method.
     *
     * @param name the name.
     * @param description the description.
     * @param members a Collection of the group members.
     * @param administrators a Collection of the group administrators.
     */
    public Group(String name, String description, Collection<JID> members,
            Collection<JID> administrators)
    {
        this.groupManager = GroupManager.getInstance();
        this.provider = groupManager.getProvider();
        this.name = name;
        this.description = description;
        this.members = new HashSet<JID>(members);
        this.administrators = new HashSet<JID>(administrators);
    }

    /**
     * Constructs a new group. Note: this constructor is intended for implementors of the
     * {@link GroupProvider} interface. To create a new group, use the
     * {@link GroupManager#createGroup(String)} method.
     *
     * @param name the name.
     * @param description the description.
     * @param members a Collection of the group members.
     * @param administrators a Collection of the group administrators.
     * @param properties a Map of properties with names and its values.
     */
    public Group(String name, String description, Collection<JID> members,
            Collection<JID> administrators, Map<String, String> properties)
    {
        this.groupManager = GroupManager.getInstance();
        this.provider = groupManager.getProvider();
        this.name = name;
        this.description = description;
        this.members = new HashSet<JID>(members);
        this.administrators = new HashSet<JID>(administrators);

        this.properties = new ConcurrentHashMap<String, String>();
        // Load the properties that this groups has in the DB
        propsProvider.loadProperties(name);

        // Check if we have to create or update some properties
        for (Map.Entry<String, String> property : properties.entrySet()) {
            // If the DB contains this property
            if (this.properties.containsKey(property.getKey())) {
                // then check if we need to update it
                if (!property.getValue().equals(this.properties.get(property.getKey()))) {
                    // update the properties map
                    this.properties.put(property.getKey(), property.getValue());
                    // and the DB
                    propsProvider.updateProperty(name, property.getKey(), property.getValue());
                }
            } // else we need to add it
            else {
                // add to the properties map
                this.properties.put(property.getKey(), property.getValue());
                // and insert it to the DB
                propsProvider.insertProperty(name, property.getKey(), property.getValue());
            }
        }

        // Check if we have to delete some properties
        for (String oldPropName : this.properties.keySet()) {
            if (!properties.containsKey(oldPropName)) {
                // delete it from the property map
                this.properties.remove(oldPropName);
                // delete it from the DB
                propsProvider.deleteProperty(name, oldPropName);
            }
        }
    }

    /**
     * Returns the name of the group. For example, 'XYZ Admins'.
     *
     * @return the name of the group.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of the group. For example, 'XYZ Admins'. This
     * method is restricted to those with group administration permission.
     *
     * @param name the name for the group.
     */
    public void setName(String name) {
        if (name == this.name || (name != null && name.equals(this.name)) || provider.isReadOnly())
        {
            // Do nothing
            return;
        }
        try {
            String originalName = this.name;
            provider.setName(this.name, name);
            groupManager.groupCache.remove(this.name);
            this.name = name;
            groupManager.groupCache.put(name, this);

            // Fire event.
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("type", "nameModified");
            params.put("originalValue", originalName);
            GroupEventDispatcher.dispatchEvent(this, GroupEventDispatcher.EventType.group_modified,
                    params);
        }
        catch (Exception e) {
            Log.error(e.getMessage(), e);
        }
    }

    /**
     * Returns the description of the group. The description often
     * summarizes a group's function, such as 'Administrators of the XYZ forum'.
     *
     * @return the description of the group.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description of the group. The description often
     * summarizes a group's function, such as 'Administrators of
     * the XYZ forum'. This method is restricted to those with group
     * administration permission.
     *
     * @param description the description of the group.
     */
    public void setDescription(String description) {
        if (description == this.description || (description != null && description.equals(this.description)) ||
                provider.isReadOnly()) {
            // Do nothing
            return;
        }
        try {
            String originalDescription = this.description;
            provider.setDescription(name, description);
            this.description = description;
            // Fire event.
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("type", "descriptionModified");
            params.put("originalValue", originalDescription);
            GroupEventDispatcher.dispatchEvent(this,
                    GroupEventDispatcher.EventType.group_modified, params);
        }
        catch (Exception e) {
            Log.error(e.getMessage(), e);
        }
    }

    @Override
	public String toString() {
        return name;
    }

    /**
     * Returns all extended properties of the group. Groups have an arbitrary
     * number of extended properties. The returned collection can be modified
     * to add new properties or remove existing ones.
     *
     * @return the extended properties.
     */
    public Map<String,String> getProperties() {
        synchronized (this) {
            if (properties == null) {
                properties = new ConcurrentHashMap<String, String>();
                propsProvider.loadProperties(name);
            }
        }
        // Return a wrapper that will intercept add and remove commands.
        return new PropertiesMap();
    }

    /**
     * Returns a Collection of the group administrators.
     *
     * @return a Collection of the group administrators.
     */
    public Collection<JID> getAdmins() {
        // Return a wrapper that will intercept add and remove commands.
        return new MemberCollection(administrators, true);
    }

    /**
     * Returns a Collection of the group members.
     *
     * @return a Collection of the group members.
     */
    public Collection<JID> getMembers() {
        // Return a wrapper that will intercept add and remove commands.
        return new MemberCollection(members, false);
    }

    /**
     * Returns true if the provided JID belongs to a user that is part of the group.
     *
     * @param user the JID address of the user to check.
     * @return true if the specified user is a group user.
     */
    public boolean isUser(JID user) {
        // Make sure that we are always checking bare JIDs
        if (user != null && user.getResource() != null) {
            user = new JID(user.toBareJID());
        }
        return user != null && (members.contains(user) || administrators.contains(user));
    }

    /**
     * Returns true if the provided username belongs to a user of the group.
     *
     * @param username the username to check.
     * @return true if the provided username belongs to a user of the group.
     */
    public boolean isUser(String username) {
        if  (username != null) {
            return isUser(XMPPServer.getInstance().createJID(username, null, true));
        }
        else {
            return false;
        }
    }

    public int getCachedSize() {
        // Approximate the size of the object in bytes by calculating the size
        // of each field.
        int size = 0;
        size += CacheSizes.sizeOfObject();              // overhead of object
        size += CacheSizes.sizeOfString(name);
        size += CacheSizes.sizeOfString(description);
        size += CacheSizes.sizeOfMap(properties);

        for (JID member: members) {
            size += CacheSizes.sizeOfString(member.toString());
        }
        for (JID admin: administrators) {
            size += CacheSizes.sizeOfString(admin.toString());
        }

        return size;
    }

    @Override
	public int hashCode() {
        return name.hashCode();
    }

    @Override
	public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object != null && object instanceof Group) {
            return name.equals(((Group)object).getName());
        }
        else {
            return false;
        }
    }
    /**
     * Collection implementation that notifies the GroupProvider of any
     * changes to the collection.
     */
    private class MemberCollection extends AbstractCollection<JID> {

        private Collection<JID> users;
        private boolean adminCollection;

        public MemberCollection(Collection<JID> users, boolean adminCollection) {
            this.users = users;
            this.adminCollection = adminCollection;
        }

        @Override
		public Iterator<JID> iterator() {
            return new Iterator<JID>() {

                Iterator<JID> iter = users.iterator();
                JID current = null;

                public boolean hasNext() {
                    return iter.hasNext();
                }

                public JID next() {
                    current = iter.next();
                    return current;
                }

                public void remove() {
                    if (current == null) {
                        throw new IllegalStateException();
                    }
                    // Do nothing if the provider is read-only.
                    if (provider.isReadOnly()) {
                        return;
                    }
                    JID user = current;
                    // Remove the user from the collection in memory.
                    iter.remove();
                    // Remove the group user from the backend store.
                    provider.deleteMember(name, user);
                    // Fire event.
                    if (adminCollection) {
                        Map<String, String> params = new HashMap<String, String>();
                        params.put("admin", user.toString());
                        GroupEventDispatcher.dispatchEvent(Group.this,
                                GroupEventDispatcher.EventType.admin_removed, params);
                    }
                    else {
                        Map<String, String> params = new HashMap<String, String>();
                        params.put("member", user.toString());
                        GroupEventDispatcher.dispatchEvent(Group.this,
                                GroupEventDispatcher.EventType.member_removed, params);
                    }
                }
            };
        }

        @Override
		public int size() {
            return users.size();
        }

        @Override
		public boolean add(JID member) {
            // Do nothing if the provider is read-only.
            if (provider.isReadOnly()) {
                return false;
            }
            JID user = member;
            // Find out if the user was already a group user.
            boolean alreadyGroupUser;
            if (adminCollection) {
                alreadyGroupUser = members.contains(user);
            }
            else {
                alreadyGroupUser = administrators.contains(user);
            }
            if (users.add(user)) {
                if (alreadyGroupUser) {
                    // Update the group user privileges in the backend store.
                    provider.updateMember(name, user, adminCollection);
                }
                else {
                    // Add the group user to the backend store.
                    provider.addMember(name, user, adminCollection);
                }

                // Fire event.
                if (adminCollection) {
                    Map<String, String> params = new HashMap<String, String>();
                    params.put("admin", user.toString());
                    if (alreadyGroupUser) {
                        GroupEventDispatcher.dispatchEvent(Group.this,
                                    GroupEventDispatcher.EventType.member_removed, params);
                    }
                    GroupEventDispatcher.dispatchEvent(Group.this,
                                GroupEventDispatcher.EventType.admin_added, params);
                }
                else {
                    Map<String, String> params = new HashMap<String, String>();
                    params.put("member", user.toString());
                    if (alreadyGroupUser) {
                        GroupEventDispatcher.dispatchEvent(Group.this,
                                    GroupEventDispatcher.EventType.admin_removed, params);
                    }
                    GroupEventDispatcher.dispatchEvent(Group.this,
                                GroupEventDispatcher.EventType.member_added, params);
                }
                // If the user was a member that became an admin or vice versa then remove the
                // user from the other collection
                if (alreadyGroupUser) {
                    if (adminCollection) {
                        if (members.contains(user)) {
                            members.remove(user);
                        }
                    }
                    else {
                        if (administrators.contains(user)) {
                            administrators.remove(user);
                        }
                    }
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Map implementation that updates the database when properties are modified.
     */
    private class PropertiesMap extends AbstractMap<String, String> {

        @Override
		public String put(String key, String value) {
            if (key == null || value == null) {
                throw new NullPointerException();
            }
            Map<String, String> eventParams = new HashMap<String, String>();
            String answer;
            String keyString = key;
            synchronized (keyString.intern()) {
                if (properties.containsKey(keyString)) {
                    String originalValue = properties.get(keyString);

                    // if is the same value don't update it.
                    if (originalValue != null && originalValue.equals(value)) {
                        return value;
                    }
                    answer = properties.put(keyString, value);
                    propsProvider.updateProperty(name, keyString, value);
                    // Configure event.
                    eventParams.put("type", "propertyModified");
                    eventParams.put("propertyKey", key);
                    eventParams.put("originalValue", originalValue);
                }
                else {
                    answer = properties.put(keyString, value);
                    propsProvider.insertProperty(name, keyString, value);
                    // Configure event.
                    eventParams.put("type", "propertyAdded");
                    eventParams.put("propertyKey", key);
                }
            }
            // Fire event.
            GroupEventDispatcher.dispatchEvent(Group.this,
                    GroupEventDispatcher.EventType.group_modified, eventParams);
            return answer;
        }

        @Override
		public Set<Map.Entry<String, String>> entrySet() {
            return new PropertiesEntrySet();
        }
    }

    /**
     * Set implementation that updates the database when properties are deleted.
     */
    private class PropertiesEntrySet extends AbstractSet<Map.Entry<String, String>> {

        @Override
		public int size() {
            return properties.entrySet().size();
        }

        @Override
		public Iterator<Map.Entry<String, String>> iterator() {
            return new Iterator<Map.Entry<String, String>>() {

                Iterator<Map.Entry<String, String>> iter = properties.entrySet().iterator();
                Map.Entry<String, String> current = null;

                public boolean hasNext() {
                    return iter.hasNext();
                }

                public Map.Entry<String, String> next() {
                    current = iter.next();
                    return current;
                }

                public void remove() {
                    if (current == null) {
                        throw new IllegalStateException();
                    }
                    String key = current.getKey();
                    propsProvider.deleteProperty(name, key);
                    iter.remove();
                    // Fire event.
                    Map<String, Object> params = new HashMap<String, Object>();
                    params.put("type", "propertyDeleted");
                    params.put("propertyKey", key);
                    GroupEventDispatcher.dispatchEvent(Group.this,
                        GroupEventDispatcher.EventType.group_modified, params);
                }
            };
        }
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        ExternalizableUtil.getInstance().writeSafeUTF(out, name);
        ExternalizableUtil.getInstance().writeBoolean(out, description != null);
        if (description != null) {
            ExternalizableUtil.getInstance().writeSafeUTF(out, description);
        }
        ExternalizableUtil.getInstance().writeSerializableCollection(out, members);
        ExternalizableUtil.getInstance().writeSerializableCollection(out, administrators);
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        groupManager = GroupManager.getInstance();
        provider = groupManager.getProvider();

        name = ExternalizableUtil.getInstance().readSafeUTF(in);
        if (ExternalizableUtil.getInstance().readBoolean(in)) {
            description = ExternalizableUtil.getInstance().readSafeUTF(in);
        }
        members= new HashSet<JID>();
        administrators = new HashSet<JID>();
        ExternalizableUtil.getInstance().readSerializableCollection(in, members, getClass().getClassLoader());
        ExternalizableUtil.getInstance().readSerializableCollection(in, administrators, getClass().getClassLoader());
    }
}
/**
 * $RCSfile: PresenceManagerImpl.java,v $
 * $Revision: 3128 $
 * $Date: 2005-11-30 15:31:54 -0300 (Wed, 30 Nov 2005) $
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

package org.jivesoftware.openfire.spi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.jivesoftware.openfire.PacketDeliverer;
import org.jivesoftware.openfire.PresenceManager;
import org.jivesoftware.openfire.RoutingTable;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.component.InternalComponentManager;
import org.jivesoftware.openfire.container.BasicModule;
import org.jivesoftware.openfire.event.UserEventDispatcher;
import org.jivesoftware.openfire.event.UserEventListener;
import org.jivesoftware.openfire.handler.PresenceUpdateHandler;
import org.jivesoftware.openfire.privacy.PrivacyList;
import org.jivesoftware.openfire.privacy.PrivacyListManager;
import org.jivesoftware.openfire.provider.PresenceProvider;
import org.jivesoftware.openfire.provider.PresenceProvider.TimePresence;
import org.jivesoftware.openfire.provider.ProviderFactory;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.roster.RosterItem;
import org.jivesoftware.openfire.roster.RosterManager;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

/**
 * Simple in memory implementation of the PresenceManager interface.
 *
 * @author Iain Shigeoka
 */
public class PresenceManagerImpl extends BasicModule implements
		PresenceManager, UserEventListener {

	private static final Logger Log = LoggerFactory
			.getLogger(PresenceManagerImpl.class);

	private static final String NULL_STRING = "NULL";

	private RoutingTable routingTable;
	private SessionManager sessionManager;
	private UserManager userManager;
	private RosterManager rosterManager;
	private XMPPServer server;
	private PacketDeliverer deliverer;
	private PresenceUpdateHandler presenceUpdateHandler;

	private InternalComponentManager componentManager;

	private Cache<String, Long> lastActivityCache;
	private Cache<String, String> offlinePresenceCache;

	/**
	 * Provider for underlying storage
	 */
	private final PresenceProvider provider = ProviderFactory
			.getPresenceProvider();

	public PresenceManagerImpl() {
		super("Presence manager");
	}

	public boolean isAvailable(User user) {
		return sessionManager.getActiveSessionCount(user.getUsername()) > 0;
	}

	public Presence getPresence(User user) {
		if (user == null) {
			return null;
		}
		Presence presence = null;

		for (ClientSession session : sessionManager.getSessions(user
				.getUsername())) {
			if (presence == null) {
				presence = session.getPresence();
			} else {
				// Get the ordinals of the presences to compare. If no ordinal
				// is available then
				// assume a value of -1
				int o1 = presence.getShow() != null ? presence.getShow()
						.ordinal() : -1;
				int o2 = session.getPresence().getShow() != null ? session
						.getPresence().getShow().ordinal() : -1;
				// Compare the presences' show ordinals
				if (o1 > o2) {
					presence = session.getPresence();
				}
			}
		}
		return presence;
	}

	public Collection<Presence> getPresences(String username) {
		if (username == null) {
			return null;
		}
		List<Presence> presences = new ArrayList<Presence>();

		for (ClientSession session : sessionManager.getSessions(username)) {
			presences.add(session.getPresence());
		}
		return Collections.unmodifiableCollection(presences);
	}

	public String getLastPresenceStatus(User user) {
		String username = user.getUsername();
		String presenceStatus = null;
		String presenceXML = offlinePresenceCache.get(username);
		if (presenceXML == null) {
			loadOfflinePresence(username);
		}
		presenceXML = offlinePresenceCache.get(username);
		if (presenceXML != null) {
			// If the cached answer is no data, return null.
			if (presenceXML.equals(NULL_STRING)) {
				return null;
			}
			// Otherwise, parse out the status from the XML.
			try {
				// Parse the element
				Document element = DocumentHelper.parseText(presenceXML);
				presenceStatus = element.getRootElement().elementTextTrim(
						"status");
			} catch (DocumentException e) {
				Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
			}
		}
		return presenceStatus;
	}

	public long getLastActivity(User user) {
		String username = user.getUsername();
		long lastActivity = PresenceProvider.NULL_LONG;
		Long offlineDate = lastActivityCache.get(username);
		if (offlineDate == null) {
			loadOfflinePresence(username);
		}
		offlineDate = lastActivityCache.get(username);
		if (offlineDate != null) {
			// If the cached answer is no data, return -1.
			if (offlineDate == PresenceProvider.NULL_LONG) {
				return PresenceProvider.NULL_LONG;
			} else {
				try {
					lastActivity = (System.currentTimeMillis() - offlineDate);
				} catch (NumberFormatException e) {
					Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
				}
			}
		}
		return lastActivity;
	}

	public void userAvailable(Presence presence) {
		// Delete the last unavailable presence of this user since the user is
		// now
		// available. Only perform this operation if this is an available
		// presence sent to
		// THE SERVER and the presence belongs to a local user.
		if (presence.getTo() == null && server.isLocal(presence.getFrom())) {
			String username = presence.getFrom().getNode();
			if (username == null || !userManager.isRegisteredUser(username)) {
				// Ignore anonymous users
				return;
			}

			// Optimization: only delete the unavailable presence information if
			// this
			// is the first session created on the server.
			if (sessionManager.getSessionCount(username) > 1) {
				return;
			}

			provider.deleteOfflinePresenceFromDB(username);

			// Remove data from cache.
			offlinePresenceCache.remove(username);
			lastActivityCache.remove(username);
		}
	}

	public void userUnavailable(Presence presence) {
		// Only save the last presence status and keep track of the time when
		// the user went
		// offline if this is an unavailable presence sent to THE SERVER and the
		// presence belongs
		// to a local user.
		if (presence.getTo() == null && server.isLocal(presence.getFrom())) {
			String username = presence.getFrom().getNode();
			if (username == null || !userManager.isRegisteredUser(username)) {
				// Ignore anonymous users
				return;
			}

			// If the user has any remaining sessions, don't record the offline
			// info.
			if (sessionManager.getActiveSessionCount(username) > 0) {
				return;
			}

			String offlinePresence = null;
			// Save the last unavailable presence of this user if the presence
			// contains any
			// child element such as <status>.
			if (!presence.getElement().elements().isEmpty()) {
				offlinePresence = presence.toXML();
			}
			// Keep track of the time when the user went offline
			java.util.Date offlinePresenceDate = new java.util.Date();

			boolean addedToCache;
			if (offlinePresence == null) {
				addedToCache = !NULL_STRING.equals(offlinePresenceCache.put(
						username, NULL_STRING));
			} else {
				addedToCache = !offlinePresence.equals(offlinePresenceCache
						.put(username, offlinePresence));
			}
			if (!addedToCache) {
				return;
			}
			lastActivityCache.put(username, offlinePresenceDate.getTime());

			provider.insertOfflinePresenceIntoDB(username, offlinePresence,
					offlinePresenceDate);
		}
	}

	public void handleProbe(Presence packet) throws UnauthorizedException {
		String username = packet.getTo().getNode();
		try {
			Roster roster = rosterManager.getRoster(username);
			RosterItem item = roster.getRosterItem(packet.getFrom());
			if (item.getSubStatus() == RosterItem.SUB_FROM
					|| item.getSubStatus() == RosterItem.SUB_BOTH) {
				probePresence(packet.getFrom(), packet.getTo());
			} else {
				PacketError.Condition error = PacketError.Condition.not_authorized;
				if ((item.getSubStatus() == RosterItem.SUB_NONE && item
						.getRecvStatus() != RosterItem.RECV_SUBSCRIBE)
						|| (item.getSubStatus() == RosterItem.SUB_TO && item
								.getRecvStatus() != RosterItem.RECV_SUBSCRIBE)) {
					error = PacketError.Condition.forbidden;
				}
				Presence presenceToSend = new Presence();
				presenceToSend.setError(error);
				presenceToSend.setTo(packet.getFrom());
				presenceToSend.setFrom(packet.getTo());
				deliverer.deliver(presenceToSend);
			}
		} catch (UserNotFoundException e) {
			Presence presenceToSend = new Presence();
			presenceToSend.setError(PacketError.Condition.forbidden);
			presenceToSend.setTo(packet.getFrom());
			presenceToSend.setFrom(packet.getTo());
			deliverer.deliver(presenceToSend);
		}
	}

	public boolean canProbePresence(JID prober, String probee)
			throws UserNotFoundException {
		RosterItem item = rosterManager.getRoster(probee).getRosterItem(prober);
		return item.getSubStatus() == RosterItem.SUB_FROM
				|| item.getSubStatus() == RosterItem.SUB_BOTH;
	}

	public void probePresence(JID prober, JID probee) {
		try {
			if (server.isLocal(probee)) {
				// Local probers should receive presences of probee in all
				// connected resources
				Collection<JID> proberFullJIDs = new ArrayList<JID>();
				if (prober.getResource() == null && server.isLocal(prober)) {
					for (ClientSession session : sessionManager
							.getSessions(prober.getNode())) {
						proberFullJIDs.add(session.getAddress());
					}
				} else {
					proberFullJIDs.add(prober);
				}
				// If the probee is a local user then don't send a probe to the
				// contact's server.
				// But instead just send the contact's presence to the prober
				Collection<ClientSession> sessions = sessionManager
						.getSessions(probee.getNode());
				if (sessions.isEmpty()) {
					// If the probee is not online then try to retrieve his last
					// unavailable
					// presence which may contain particular information and
					// send it to the
					// prober
					String presenceXML = offlinePresenceCache.get(probee
							.getNode());
					if (presenceXML == null) {
						loadOfflinePresence(probee.getNode());
					}
					presenceXML = offlinePresenceCache.get(probee.getNode());
					if (presenceXML != null && !NULL_STRING.equals(presenceXML)) {
						try {
							// Parse the element
							Document element = DocumentHelper
									.parseText(presenceXML);
							// Create the presence from the parsed element
							Presence presencePacket = new Presence(
									element.getRootElement());
							presencePacket.setFrom(probee.toBareJID());
							// Check if default privacy list of the probee
							// blocks the
							// outgoing presence
							PrivacyList list = PrivacyListManager.getInstance()
									.getDefaultPrivacyList(probee.getNode());
							// Send presence to all prober's resources
							for (JID receipient : proberFullJIDs) {
								presencePacket.setTo(receipient);
								if (list == null
										|| !list.shouldBlockPacket(presencePacket)) {
									// Send the presence to the prober
									deliverer.deliver(presencePacket);
								}
							}
						} catch (Exception e) {
							Log.error(LocaleUtils
									.getLocalizedString("admin.error"), e);
						}
					}
				} else {
					// The contact is online so send to the prober all the
					// resources where the
					// probee is connected
					for (ClientSession session : sessions) {
						// Create presence to send from probee to prober
						Presence presencePacket = session.getPresence()
								.createCopy();
						presencePacket.setFrom(session.getAddress());
						// Check if a privacy list of the probee blocks the
						// outgoing presence
						PrivacyList list = session.getActiveList();
						list = list == null ? session.getDefaultList() : list;
						// Send presence to all prober's resources
						for (JID receipient : proberFullJIDs) {
							presencePacket.setTo(receipient);
							if (list != null) {
								if (list.shouldBlockPacket(presencePacket)) {
									// Default list blocked outgoing presence so
									// skip this session
									continue;
								}
							}
							try {
								deliverer.deliver(presencePacket);
							} catch (Exception e) {
								Log.error(LocaleUtils
										.getLocalizedString("admin.error"), e);
							}
						}
					}
				}
			} else {
				if (routingTable.hasComponentRoute(probee)) {
					// If the probee belongs to a component then ask the
					// component to process the
					// probe presence
					Presence presence = new Presence();
					presence.setType(Presence.Type.probe);
					presence.setFrom(prober);
					presence.setTo(probee);
					routingTable.routePacket(probee, presence, true);
				} else {
					// Check if the probee may be hosted by this server
					/*
					 * String serverDomain = server.getServerInfo().getName();
					 * if (!probee.getDomain().contains(serverDomain)) {
					 */
					if (server.isRemote(probee)) {
						// Send the probe presence to the remote server
						Presence probePresence = new Presence();
						probePresence.setType(Presence.Type.probe);
						probePresence.setFrom(prober);
						probePresence.setTo(probee.toBareJID());
						// Send the probe presence
						deliverer.deliver(probePresence);
					} else {
						// The probee may be related to a component that has not
						// yet been connected so
						// we will keep a registry of this presence probe. The
						// component will answer
						// this presence probe when he becomes online
						componentManager.addPresenceRequest(prober, probee);
					}
				}
			}
		} catch (Exception e) {
			Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
		}
	}

	public void sendUnavailableFromSessions(JID recipientJID, JID userJID) {
		if (XMPPServer.getInstance().isLocal(userJID)
				&& userManager.isRegisteredUser(userJID.getNode())) {
			for (ClientSession session : sessionManager.getSessions(userJID
					.getNode())) {
				// Do not send an unavailable presence if the user sent a direct
				// available presence
				if (presenceUpdateHandler.hasDirectPresence(
						session.getAddress(), recipientJID)) {
					continue;
				}
				Presence presencePacket = new Presence();
				presencePacket.setType(Presence.Type.unavailable);
				presencePacket.setFrom(session.getAddress());
				// Ensure that unavailable presence is sent to all receipient's
				// resources
				Collection<JID> recipientFullJIDs = new ArrayList<JID>();
				if (server.isLocal(recipientJID)) {
					for (ClientSession targetSession : sessionManager
							.getSessions(recipientJID.getNode())) {
						recipientFullJIDs.add(targetSession.getAddress());
					}
				} else {
					recipientFullJIDs.add(recipientJID);
				}
				for (JID jid : recipientFullJIDs) {
					presencePacket.setTo(jid);
					try {
						deliverer.deliver(presencePacket);
					} catch (Exception e) {
						Log.error(
								LocaleUtils.getLocalizedString("admin.error"),
								e);
					}
				}
			}
		}
	}

	public void userCreated(User user, Map<String, Object> params) {
		// Do nothing
	}

	public void userDeleting(User user, Map<String, Object> params) {
		// Delete user information
		provider.deleteOfflinePresenceFromDB(user.getUsername());
	}

	public void userModified(User user, Map<String, Object> params) {
		// Do nothing
	}

	// #####################################################################
	// Module management
	// #####################################################################

	@Override
	public void initialize(XMPPServer server) {
		super.initialize(server);
		this.server = server;

		offlinePresenceCache = CacheFactory
				.createCache("Offline Presence Cache");
		lastActivityCache = CacheFactory.createCache("Last Activity Cache");

		deliverer = server.getPacketDeliverer();
		sessionManager = server.getSessionManager();
		userManager = server.getUserManager();
		presenceUpdateHandler = server.getPresenceUpdateHandler();
		rosterManager = server.getRosterManager();
		routingTable = server.getRoutingTable();
	}

	@Override
	public void start() throws IllegalStateException {
		super.start();
		// Use component manager for Presence Updates.
		componentManager = InternalComponentManager.getInstance();
		// Listen for user deletion events
		UserEventDispatcher.addListener(this);

	}

	@Override
	public void stop() {
		// Clear the caches when stopping the module.
		offlinePresenceCache.clear();
		lastActivityCache.clear();
		// Stop listening for user deletion events
		UserEventDispatcher.removeListener(this);
	}

	/**
	 * Loads offline presence data for the user into cache.
	 *
	 * @param username
	 *            the username.
	 */
	private void loadOfflinePresence(String username) {
		Lock lock = CacheFactory.getLock(username, offlinePresenceCache);
		try {
			lock.lock();
			if (!offlinePresenceCache.containsKey(username)
					|| !lastActivityCache.containsKey(username)) {
				TimePresence tp = provider.loadOfflinePresence(username);

				offlinePresenceCache.put(username, tp.getPresence());
				lastActivityCache.put(username, tp.getLastActivity());
			}
		} finally {
			lock.unlock();
		}
	}
}

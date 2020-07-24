/**
 * GUIAgent
 * 
 * That agent presents data gathered by agent system through GUI
 */

package agentControlSystem;

import jade.core.Agent;

import java.net.InetAddress;

import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.proto.ContractNetInitiator;
import jade.proto.SubscriptionInitiator;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

public class GUIAgent extends Agent {
	
	// States of connection
	private static final String STARTING_CONNECTION = "Starting_connection";
	private static final String CONNECTION_RUNNING = "Connection_running";
	private static final String CONNECTION_STOPPED_BY_USER = "Stopped_by_user";
	private static final String CONNECTION_AGENT_TERMINATED = "Connection_agent_terminated";
	private static final String PLANT_DISCONNECTED = "Plant_disconnected";
	
	private static final String SUBSCRIPTION_ID = "Subscription_id";
	
	// One GUI is used for establishing connections with plants, and another for showing results
	private TCPClientConnectionGui connectionGui;
	private boolean connectionGuiActive;
	private ResultsGui resultsGui;
	private boolean resultsGuiActive;
	
	private HashMap<String, String> connectionStates;	
	private AID[] connectionAgents;
	
	/* Agent methods */
	protected void setup() {
		
		connectionStates = new HashMap<String, String>();
		//only gui windows need to be created in setup
		resultsGui = new ResultsGui(this);
		resultsGuiActive = false;
		connectionGui = new TCPClientConnectionGui(this);
		connectionGui.showGui();
		connectionGuiActive = true;
	}
	
	protected void takeDown() {
		resultsGui.dispose();
		connectionGui.dispose();
	}
	
	/* GUI methods */
	
	/**
	 * Method invoked by connection GUI to connect to server.
	 * @param IP - server's IP
	 * @param port - server's port
	 */
	public void monitorPlant(final String IP, int port) {
		addBehaviour(new OneShotBehaviour(this) {
			public void action() {
				try {
					InetAddress IPaddr = InetAddress.getByName(IP); // just consistency check
					final String convID = IP + ":" + String.valueOf(port) + "_" + myAgent.getName();
					if (connectionStates.containsKey(convID) == false) {
						connectionStates.put(convID, STARTING_CONNECTION);
						// Creating behaviour that wii fetch data from selected plant.
						myAgent.addBehaviour(new MonitorPlantBehaviour(myAgent, IP, port, convID));
					} else {
						connectionGui.printConnectionError("Connection to that plant has already begun.");
					}	
				} catch (Exception e) {
					connectionGui.printConnectionError("Exception occured.");
				}
			}
		});
	}
	
	/**
	 * Method for handling closing connection GUI.
	 */
	public void handleConnectionGuiClosing() {
		addBehaviour(new OneShotBehaviour(this) {
			public void action() {
				System.out.println(myAgent.getAID().getName() + " - Connection GUI closed");
				connectionGuiActive = false;
				if (resultsGuiActive) {
					resultsGui.showInfoDialog("Connection GUI closed, cannot establish new connections.");			
				} else if (connectionStates.isEmpty()) {
					// Inactive results GUI and empty connectionStates means, that there
					// is no active connection. With connection GUI closing there won't
					// be any possibility to establish any connection so agent can be deleted
					myAgent.doDelete();
				}
			}
		});
	}
	
	/**
	 * Method for handling closing results GUI, closing that GUI finally results in deleting agent.
	 */
	public void handleResultGuiClosing() {
		// Every running connection is flagged as stopped by user. I do not
		// try to delete agent at this stage, firstly I want all connections to finish/abort.
		// Thanks to that agent will clean up after himself, when no connections will
		// be active agent is going to be deleted.
		addBehaviour(new OneShotBehaviour(this) {
			public void action() {
				System.out.println(myAgent.getAID().getName() + " - Result GUI closed");
				resultsGuiActive = false;
				if (!connectionStates.isEmpty()) {
					for (Map.Entry<String, String> entry : connectionStates.entrySet()) {
						entry.setValue(CONNECTION_STOPPED_BY_USER);
					}
				}
			}
		});

	}
	
	/**
	 * Method for handling stopping connection with plant by user.
	 * @param connectionID - ID of plant that should be disconnected.
	 */
	public void handleStopCommand(final String connectionID) {
		// Connection with selected plant is flagged as stopped by user, FSM behaviour will
		// do all the cleanup and stop.
		
		addBehaviour(new OneShotBehaviour(this) {
			public void action() {
				System.out.println(myAgent.getAID().getName() + " - Stop command for monitoring " + connectionID + " detected");
				connectionStates.replace(connectionID, CONNECTION_STOPPED_BY_USER);
			}
		});		
	}
	
	/* Behaviours */
	
	private class MonitorPlantBehaviour extends FSMBehaviour {
		
		// Names of states in finite state machine behaviour
		private static final String CHECK_CONNECTION_AGENTS = "Check_connection_agents";
		private static final String REPEAT_CHECK_CONNECTION_AGENTS = "Repeat_check_connection_agents";
		private static final String CALL_FOR_CONNECTION = "Call_for_connection";
		private static final String SUBSCRIBE_TO_PLANT = "Subscribe_to_plant";
		private static final String HANDLE_CLOSING_CONNECTION = "Handle_closing_connection";
		
		
		public MonitorPlantBehaviour (Agent a, final String IP, int port, final String sub_ID) {
			super(a);
			
			DataStore ds = getDataStore();
			ds.put(ConnectionInitiator.IP_NUM, IP);
			ds.put(ConnectionInitiator.PORT_NUM, String.valueOf(port));
			ds.put(SUBSCRIPTION_ID, sub_ID);
			
			// Registering all state transitions
			registerTransition(CHECK_CONNECTION_AGENTS, REPEAT_CHECK_CONNECTION_AGENTS, 0);
			registerTransition(CHECK_CONNECTION_AGENTS, HANDLE_CLOSING_CONNECTION, -2);
			registerDefaultTransition(CHECK_CONNECTION_AGENTS, CALL_FOR_CONNECTION);
			registerTransition(REPEAT_CHECK_CONNECTION_AGENTS, REPEAT_CHECK_CONNECTION_AGENTS, 0, 
					new String[] {REPEAT_CHECK_CONNECTION_AGENTS});
			registerTransition(REPEAT_CHECK_CONNECTION_AGENTS, HANDLE_CLOSING_CONNECTION, -2);
			registerDefaultTransition(REPEAT_CHECK_CONNECTION_AGENTS, CALL_FOR_CONNECTION);
			registerTransition(CALL_FOR_CONNECTION, REPEAT_CHECK_CONNECTION_AGENTS, 0, 
					new String[] {CALL_FOR_CONNECTION, REPEAT_CHECK_CONNECTION_AGENTS});
			registerTransition(CALL_FOR_CONNECTION, HANDLE_CLOSING_CONNECTION, -1);
			registerTransition(CALL_FOR_CONNECTION, HANDLE_CLOSING_CONNECTION, -2);
			registerDefaultTransition(CALL_FOR_CONNECTION, SUBSCRIBE_TO_PLANT);
			registerTransition(SUBSCRIBE_TO_PLANT, CHECK_CONNECTION_AGENTS, 0,
					new String[] {CHECK_CONNECTION_AGENTS, REPEAT_CHECK_CONNECTION_AGENTS, CALL_FOR_CONNECTION,
							SUBSCRIBE_TO_PLANT});
			registerDefaultTransition(SUBSCRIBE_TO_PLANT, HANDLE_CLOSING_CONNECTION);
			
			Behaviour b = new ConnectionAgentsChecker(myAgent);
			registerFirstState(b, CHECK_CONNECTION_AGENTS);
			b.setDataStore(ds);
			
			b = new RepeatingConnectionAgentsChecker(myAgent, 2000);
			registerState(b, REPEAT_CHECK_CONNECTION_AGENTS);
			b.setDataStore(ds);
			
			b = new ConnectionInitiator(myAgent, getDataStore());
			registerState(b, CALL_FOR_CONNECTION);
			
			b = new SubscribeToPlant(myAgent, getDataStore());
			registerState(b, SUBSCRIBE_TO_PLANT);
			
			b = new CloseConnection(myAgent);
			registerLastState(b, HANDLE_CLOSING_CONNECTION);
			b.setDataStore(ds);
		}
	}
	
	private class ConnectionAgentsChecker extends OneShotBehaviour {
		
		ConnectionAgentsChecker(Agent a) {
			super(a);
		}
		
		public void action() {
			// Calling yellow page agent for available connector agents
			DFAgentDescription template = new DFAgentDescription();
			ServiceDescription sd = new ServiceDescription();
			sd.setType("plant_connection");
			template.addServices(sd);
			
			try {
				DFAgentDescription[] result = DFService.search(myAgent, template); 
				System.out.println(myAgent.getAID().getName() + " - found following connector agents:");
				connectionAgents = new AID[result.length];
				for (int i = 0; i < result.length; ++i) {
					connectionAgents[i] = result[i].getName();
					System.out.println(connectionAgents[i].getName());
				}
			}
			catch (FIPAException fe) {
				fe.printStackTrace();
				connectionAgents = new AID[0];
			}
		}
		
		public int onEnd() {
			final String conState = connectionStates.get((String) this.getDataStore().get(SUBSCRIPTION_ID));
			if (conState.equals(CONNECTION_STOPPED_BY_USER)) {
				return -2;
			} else {
				return connectionAgents.length;
			}
		}
	}
	
	private class RepeatingConnectionAgentsChecker extends WakerBehaviour {
		
		RepeatingConnectionAgentsChecker(Agent a, long timeout) {
			super(a, timeout);
		}
		
		public void onWake() {
			// If there was no connector agents previously try to do it again.
			DFAgentDescription template = new DFAgentDescription();
			ServiceDescription sd = new ServiceDescription();
			sd.setType("plant_connection");
			template.addServices(sd);
			
			try {
				DFAgentDescription[] result = DFService.search(myAgent, template); 
				System.out.println(myAgent.getAID().getName() + " - found following connector agents:");
				connectionAgents = new AID[result.length];
				for (int i = 0; i < result.length; ++i) {
					connectionAgents[i] = result[i].getName();
					System.out.println(connectionAgents[i].getName());
				}
			}
			catch (FIPAException fe) {
				fe.printStackTrace();
				connectionAgents = new AID[0];
			}
		}
		
		public int onEnd() {
			if (connectionStates.get((String) this.getDataStore().get(SUBSCRIPTION_ID)).equals(CONNECTION_STOPPED_BY_USER)) {
				return -2;
			} else {
				return connectionAgents.length;
			}
		}
	}
	
	private class ConnectionInitiator extends ContractNetInitiator {
		// Connector agents have been found, now call them for proposals.
		public static final String IP_NUM = "IP_number";
		public static final String PORT_NUM = "port";
		
		ConnectionInitiator (Agent a, DataStore ds) {
			super(a, null, ds);
		}
		
		protected java.util.Vector prepareCfps(ACLMessage cfp) {
			System.out.println(myAgent.getAID().getName() + " - preparing CFPs to establish connection");
			cfp = new ACLMessage(ACLMessage.CFP);
			cfp.setContent((String) this.getDataStore().get(IP_NUM) + ":" + (String)this.getDataStore().get(PORT_NUM));
			cfp.setReplyByDate(new Date(System.currentTimeMillis() + 5000));
			for (int i = 0; i < connectionAgents.length; ++i) {
				cfp.addReceiver(connectionAgents[i]);
			}
			Vector v = new Vector(); // I do not like using that raw type, but this is the way it is implemented in JADE.
			v.add(cfp);
			return v;			
		}
		
		protected void handleAllResponses(Vector responses, Vector acceptances) {
			// Checking is any requested agent is available to connect.
			System.out.println(myAgent.getAID().getName() + " - handling responses");
			ACLMessage bestOffer = null;
			Vector proposingResponses = new Vector();
			
			for (int i = 0; i < responses.size(); ++i) {
				ACLMessage rsp = (ACLMessage) responses.get(i);
				if (rsp.getPerformative() == ACLMessage.PROPOSE) {
					proposingResponses.add(rsp);
					String response = rsp.getContent();
					if (response.equals("connected")) {
						bestOffer = rsp;
					} else if (response.equals("ready") && bestOffer == null) {
						bestOffer = rsp;
					}
				}
			}
			
			if (bestOffer != null) {
				System.out.println(myAgent.getAID().getName() + " - best offer found");
				for (int i = 0; i < proposingResponses.size(); ++i) {
					ACLMessage propose = (ACLMessage) proposingResponses.get(i);
					ACLMessage reply = propose.createReply();
					if (propose == bestOffer) {
						reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
						reply.setReplyByDate(new Date(System.currentTimeMillis() + 15000));
						reply.setContent((String) this.getDataStore().get(IP_NUM) + ":" + (String)this.getDataStore().get(PORT_NUM));
					} else {
						reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
					}
					acceptances.add(reply);
				}
			}
		}
		
		protected void handleInform(ACLMessage inform) {
			System.out.println(myAgent.getAID().getName() + " - getting informed about process variables");
			String informContent = inform.getContent();
			informContent.trim();
			if (informContent.length() > 0) {
				// At this stage information about available process variables is needed.
				int count = 0;
				for (int i = 0; i < informContent.length(); i++) {
					if (informContent.charAt(i) == ';') {
						count++;
					}
				}
				
				String variableNames[] = new String [count + 1];
				variableNames = informContent.split(";");
				
				String Subs_id = (String) this.getDataStore().get(SUBSCRIPTION_ID);
				String connectionState = connectionStates.get(Subs_id);
				if (!connectionState.equals(CONNECTION_STOPPED_BY_USER)) {
					System.out.println(myAgent.getAID().getName() + " - creating plant labels");
					String shortID = (String) this.getDataStore().get(IP_NUM) + ":" + (String)this.getDataStore().get(PORT_NUM) + "@" + inform.getSender().getLocalName();
					resultsGui.createPlantLabels(Subs_id, shortID, variableNames);
					if (resultsGuiActive == false) {
						System.out.println(myAgent.getAID().getName() + " - showing results GUI");
						resultsGui.showGui();
						resultsGuiActive = true;
					}
					connectionStates.replace(Subs_id, CONNECTION_RUNNING);
					this.getDataStore().put(SubscribeToPlant.RECV_AID, inform.getSender());
				}
			}
		}
		
		protected void handleFailure(ACLMessage failure) {
			// Failing to connect to plant is treated as plant disconnection.
			connectionStates.replace((String) this.getDataStore().get(SUBSCRIPTION_ID), PLANT_DISCONNECTED);
		}
		
		public int onEnd() {
			String connectionState = connectionStates.get((String) this.getDataStore().get(SUBSCRIPTION_ID));
			if (connectionState.equals(CONNECTION_RUNNING)) {
				return 1;
			} else if (connectionState.equals(PLANT_DISCONNECTED)) {
				return -1;
			} else if (connectionState.equals(CONNECTION_STOPPED_BY_USER)) {
				return -2;
			} else {
				return 0;
			}
		}
	}
	
	private class SubscribeToPlant extends SubscriptionInitiator {
		// When everything went well and conncetor agent established connection with plant this agent can initiate
		// subscription.
		public static final String RECV_AID = "Receiver_aid";
		
		SubscribeToPlant(Agent a, DataStore ds) {
			super(a, null, ds);
		}
		
		protected Vector prepareSubscriptions(ACLMessage subscription) {
			System.out.println(myAgent.getAID().getName() + " - subscribing to plant");
			Vector subsMessages = new Vector(1); // Only one subscription per behaviour is running
			ACLMessage subscriptionMessage = new ACLMessage(ACLMessage.SUBSCRIBE);
			subscriptionMessage.setConversationId((String) this.getDataStore().get(SUBSCRIPTION_ID));
			subscriptionMessage.setProtocol("subscription to plant");
			subscriptionMessage.addReceiver((AID) this.getDataStore().get(RECV_AID));
			subscriptionMessage.setReplyByDate(new Date(System.currentTimeMillis() + 2000));
			/* TODO (?) choose variables to receive */
			
			subsMessages.add(subscriptionMessage);
			return subsMessages;
		}
		
		protected void handleRefuse(ACLMessage refuse) {
			// Any refusal is treated as plant disconnection
			connectionStates.replace((String) this.getDataStore().get(SUBSCRIPTION_ID), PLANT_DISCONNECTED);
		}
		
		protected void handleOutOfSequence(ACLMessage message) {
			// Any out of sequence is treated as plant disconnection
			connectionStates.replace((String) this.getDataStore().get(SUBSCRIPTION_ID), PLANT_DISCONNECTED);
		}
		
		protected void handleInform(ACLMessage inform) {
			// That method is called every time update of plant values is received
			String connectionState = connectionStates.get((String) this.getDataStore().get(SUBSCRIPTION_ID));
			if (connectionState.contentEquals(CONNECTION_RUNNING)) {
				String messageContent = inform.getContent();
				messageContent = messageContent.trim();
				if (messageContent.length() > 0) {
					int count = 0;
					for (int i = 0; i < messageContent.length(); i++) {
						if (messageContent.charAt(i) == ';') {
							count++;
						}
					}
					
					String variablePairs[] = new String [count + 1];
					variablePairs = messageContent.split(";");
					
					String singlePair[] = new String[2];
					for (int i = 0; i < count + 1; i++) {
						singlePair[1] = "";
						singlePair = variablePairs[i].split(":", 2);
						if(singlePair[1] != "" ) {
							resultsGui.updateValue((String) this.getDataStore().get(SUBSCRIPTION_ID), singlePair[0], singlePair[1]);
						}
					}
				}
			} else {
				// Canceling subscription.
				cancel((AID) this.getDataStore().get(RECV_AID), true);
				System.out.println(myAgent.getAID().getName() + " - cancel subscription message sent to " + ((AID) this.getDataStore().get(RECV_AID)).getName());
			}
		}
		
		protected void handleFailure(ACLMessage failure) {
			final String connectionID = (String) this.getDataStore().get(SUBSCRIPTION_ID);
			if (!connectionStates.get(connectionID).equals(CONNECTION_STOPPED_BY_USER)) {
				String failureReason = failure.getContent();
				if (failureReason.equals(PLANT_DISCONNECTED)) {
					connectionStates.replace(connectionID, PLANT_DISCONNECTED);
				} else {
					connectionStates.replace(connectionID, CONNECTION_AGENT_TERMINATED);
				}
			}
		}
		
		public int onEnd() {
			String connectionState = connectionStates.get((String) this.getDataStore().get(SUBSCRIPTION_ID));
			if (connectionState.equals(CONNECTION_STOPPED_BY_USER)) {
				return 1;
			} else if (connectionState.equals(CONNECTION_AGENT_TERMINATED)) {
				return 0;
			} else {
				return -1;
			}
		}
	}
	
	private class CloseConnection extends OneShotBehaviour {
		
		CloseConnection(Agent a) {
			super(a);
		}
		
		public void action() {
			String connectionID = (String) this.getDataStore().get(SUBSCRIPTION_ID);
			
			if (connectionStates.get(connectionID).equals(PLANT_DISCONNECTED) && resultsGuiActive) {
				resultsGui.showDisconnectionDialog(connectionID);
			}
			
			if (resultsGuiActive) {
				resultsGui.closePlantLabels(connectionID);
			}
			
			connectionStates.remove(connectionID);
			boolean anyGuiClosed = !connectionGuiActive || !resultsGuiActive;
			if (connectionStates.isEmpty() && anyGuiClosed) {
				// if there is no more connections open and
				// there is no possibility to establish or observe new ones agent is deleted.
				myAgent.doDelete();
			}
		}
	}

}

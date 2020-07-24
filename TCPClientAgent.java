/**
 * TCPClientAgent
 * 
 * This agent establishes TCP connection to address it is
 * asked to connect. After that it receives data from plant
 * in XML format and forwards them to subscribed agents.
 * 
 * One client agent is connected to only one plant through its
 * entire lifespan but can send data to multiple agents.
 * 
 * Author: Jakub Pośpiech.
 */
package agentControlSystem;

import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.proto.SubscriptionResponder;
import jade.proto.SSContractNetResponder;

import java.net.*;
import java.nio.charset.Charset;
import java.io.*;
import javax.xml.stream.XMLEventReader; 
import javax.xml.stream.XMLInputFactory; 
import javax.xml.stream.XMLStreamException; 
import javax.xml.stream.events.*; 

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

public class TCPClientAgent extends Agent {
	
	/**
	 * Factory for threaded behaviour
	 */
	private ThreadedBehaviourFactory tbf;
	
	/**
	 * Variables used to establish and process TCP connection
	 */
	private Socket plantSocket;
	private BufferedReader plantReader;
	private PrintWriter plantWriter; // for now agent only reads data so plantWriter stays unused
	
	/**
	 * Connection state and declared possible states.
	 */
	private volatile String connectionState;
	
	private static final String WAITING_FOR_CONNECTION = "Waiting for conenction";
	private static final String ESTABLISHING_CONNECTION = "Establishing_connection";
	private static final String CONNECTION_ESTABLISHED = "Connection_established";
	private static final String ERROR_OCCURED = "Error occured";
	private static final String PLANT_DISCONNECTED = "Plant_disconnected";
	
	/**
	 * Maps storing values gathered from plant and information about
	 * current subscriptions.
	 */
	private Map<String, SubscriptionResponder.Subscription> subsMap;
	private Map<String, String> valMap;
	
	/**
	 * Description for yellow page agent.
	 */
	private DFAgentDescription dfd;
	
	/**
	 * Message template.
	 */
	private MessageTemplate cfpTemplate;
	
	/**
	 * Address of plant that agent is connected to.
	 */
	private PlantAddress plantAddress;
	
	protected void setup() {
		
		connectionState = WAITING_FOR_CONNECTION;
		// Initializing maps
		subsMap = Collections.synchronizedMap(new HashMap<String, SubscriptionResponder.Subscription>());
		valMap = Collections.synchronizedMap(new HashMap<String, String>());
		
		// Registering to DF agent (yellow pages)
		dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("plant_connection");
		sd.setName("agent-control-system");
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
		// Declaring call for proposals message template
		cfpTemplate = MessageTemplate.MatchPerformative(ACLMessage.CFP);
		
		// Behaviour used for reading data from plant works in separate thread.
		tbf = new ThreadedBehaviourFactory();
		addBehaviour(tbf.wrap(new ReadFromPlant(this)));
		// Starting primary behaviours
		addBehaviour(new HandleContracts(this));
		addBehaviour(new RegisterSubscriptions(this, MessageTemplate.MatchProtocol("subscription to plant")));
	}
	
	// Put agent clean-up operations here
	protected void takeDown() {
		System.out.println(getAID().getName() + " closing...");
		// Connection is open, behaviour is running in separate thread, need to clean up.
		if (connectionState != WAITING_FOR_CONNECTION) {
			if (connectionState == ESTABLISHING_CONNECTION || connectionState == CONNECTION_ESTABLISHED) {
				System.out.println(getAID().getName() + " closing connections...");
				if (connectionState == CONNECTION_ESTABLISHED) {
					// Closing established connection by sending FAILURE message to subscribed agents
					ACLMessage subscriptionMessage = new ACLMessage(ACLMessage.FAILURE);
					synchronized(subsMap) {
						for (Map.Entry<String, SubscriptionResponder.Subscription> subsEntry : subsMap.entrySet()) {
							subsEntry.getValue().notify(subscriptionMessage);
						}
					}
				}	
			}
			
			try {
				plantSocket.shutdownInput();
			} catch (IOException e) {
				System.out.println(getAID().getName() + " unexpected IOException occured, while closing input stream.");
			}
			// Wait 5s for threaded behavior to end (input stream is closed so it should end), then force interrupt them.
			if (!tbf.waitUntilEmpty(5000)) {
				tbf.interrupt();
				System.out.println(getAID().getName() + " timeout exceeded, threaded behaviours were forced interrupted.");
			}
			try {
				plantSocket.close();
			} catch (IOException e) {
				System.out.println(getAID().getName() + " unexpected IOException occured, while closing socket.");
			}			
		}
		System.out.println(getAID().getName() + " agent closed.");
	}
	
	private class HandleContracts extends CyclicBehaviour {
		
		HandleContracts(Agent a) {
			super(a);
		}
		
		public void action() {
			ACLMessage msg = myAgent.receive(cfpTemplate);
			if (msg != null) {
				// When call for proposals is received new behaviour is started that
				// performs all neded operations.
				myAgent.addBehaviour(new ContractsNegotiator(myAgent, msg));
			} else {
				// Behaviour.block() blocks only that particular behaviour, whole
				// agents works normally, behaviour is becoming active again
				// when new message is received.
				block();
			}
		}
	}
	
	private class ContractsNegotiator extends SSContractNetResponder {
		// That behaviour is used for processing call for proposals protocol messages
		// it extends template version of responder class in call for porposals protocol.
		ContractsNegotiator(Agent a, ACLMessage cfp) {
			super(a, cfp);
		}
		
		protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException, FailureException, NotUnderstoodException {
			ACLMessage reply = cfp.createReply();
			if (connectionState == WAITING_FOR_CONNECTION) {
				// Agent waits to connect to plant so sends message as proposal that it is ready to connect
				reply.setPerformative(ACLMessage.PROPOSE);
				reply.setContent("ready");
			} else if (connectionState == ESTABLISHING_CONNECTION || connectionState == CONNECTION_ESTABLISHED) {
				String connectionParams = cfp.getContent();
				String IPAndPort[] = new String[2];
				IPAndPort = connectionParams.split(":", 2);
				String IP = IPAndPort[0];
				int port = 0;
				try {
					if (IPAndPort[1] != "null") {
						port = Integer.parseInt(IPAndPort[1]);
						if (plantAddress.isSameAddress(IP, port)) {
							// Agent is connected to the same plant as required in
							// call for proposals so sends message that it is already connected to that plant.
							reply.setPerformative(ACLMessage.PROPOSE);
							reply.setContent("connected");
						} else {
							// Agent is connected to other plant than that required in
							// call for proposals so sends message refusing proposal.
							reply.setPerformative(ACLMessage.REFUSE);
						}
					}
				} catch (NumberFormatException e) {
					reply.setPerformative(ACLMessage.REFUSE);
				}
			} else {
				// Connection is closed or error occurred, refusing all proposals
				reply.setPerformative(ACLMessage.REFUSE);
			}
			return reply;
		}
		
		protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) throws FailureException {
			// Proposal to connect was accepted.
			ACLMessage reply = accept.createReply();
			StringBuilder valNamesList = new StringBuilder();
			if (connectionState == CONNECTION_ESTABLISHED) {
				// Connection has been already established so just send values names.
				synchronized(valMap){
					for(String valName : valMap.keySet()) {
						valNamesList.append(valName);
						valNamesList.append(";");
					}
				}
			} else if (connectionState == ESTABLISHING_CONNECTION) {
				// Establishing connection has been started but it is not finished yet
				// waiting a moment to let it be done.
				synchronized(valMap) {
					long timeToWait = accept.getReplyByDate().getTime() - System.currentTimeMillis() - 100;
					if (timeToWait > 0) {
						try {
							valMap.wait(timeToWait);
						} catch (InterruptedException e) {}
					}
					// If connection state has changed Inform message can be sent
					if (connectionState == CONNECTION_ESTABLISHED) {
						for(String valName : valMap.keySet()) {
							valNamesList.append(valName);
							valNamesList.append(";");
						}
					}
				}
			} else if (connectionState == WAITING_FOR_CONNECTION) {
				// Nothing has been done yet so it is time to establish connection.
				String connectionParams = accept.getContent();
				String IPAndPort[] = new String[2];
				IPAndPort = connectionParams.split(":", 2);
				String IP = IPAndPort[0];
				int port = 0;
				try {
					InetAddress IPaddr = InetAddress.getByName(IP);
					if (IPAndPort[1] != "null") {
						port = Integer.parseInt(IPAndPort[1]);
						// Initializing all variables needed to keep connection with plant
						plantAddress = new PlantAddress(IP, port);
						plantSocket = new Socket(IPaddr, port);
						plantWriter = new PrintWriter(plantSocket.getOutputStream(), true); //not used yet writing is W.I.P.
						plantReader = new BufferedReader(new InputStreamReader(plantSocket.getInputStream()));
						System.out.println(myAgent.getAID().getName() + " - establishing connection");
						
						synchronized(valMap) {
							// Variables initialized, notifying TCP connection thread (behaviour) to start read data.
							connectionState = ESTABLISHING_CONNECTION;
							valMap.notifyAll();
						}						
						
						long timeToWait = 0;
						if (accept.getReplyByDate() == null) {
							timeToWait = 10000;
						} else {
							timeToWait = accept.getReplyByDate().getTime() - System.currentTimeMillis() - 500;
						}
						System.out.println(myAgent.getAID().getName() + " - waiting for " + timeToWait + " miliseconds");
						synchronized(valMap) {
							// Now give another thread some time to read all the values that plant sent.
							if (timeToWait > 0) {
								try {
									valMap.wait(timeToWait);
								} catch (InterruptedException e) {
									System.out.println(myAgent.getAID().getName() + " - waiting interrupted");
								}
							}
							
							if (connectionState == CONNECTION_ESTABLISHED) {
								// Other thread established connection succesfully, now get values names.
								for(String valName : valMap.keySet()) {
									valNamesList.append(valName);
									valNamesList.append(";");
								}
							}
						}
					}
				} catch (Exception e) {
					System.out.println(myAgent.getAID().getName() + " - exception occured");
					e.printStackTrace();
				}
			}
			
			if (valNamesList.length() > 0) {
				// list of values names is filled, all went well send it to requesting agent.
				reply.setPerformative(ACLMessage.INFORM);
				valNamesList.setLength(valNamesList.length() - 1);
				reply.setContent(valNamesList.toString());
			} else {
				// Values names is not filled, something went wrong in the process, notify requesting agent about the failure.
				reply.setPerformative(ACLMessage.FAILURE);
			}
			System.out.println(myAgent.getAID().getName() + " - handling accept ended");
			return reply;
		}
		
	}
	
	
	private class RegisterSubscriptions extends SubscriptionResponder {
		// Behaviour used for registering subscriptions.
		RegisterSubscriptions(Agent a, MessageTemplate mt) {
			super(a,mt);
		}
		
		protected ACLMessage handleSubscription(ACLMessage subscription)
                throws NotUnderstoodException,
                       RefuseException {
			// Subscription request received staring to process.
			if (connectionState == CONNECTION_ESTABLISHED) {
				// Subscription is only accepted when connection is already established
				// (otherwise requesting agent will subscribe to nothing which has no sense).
				SubscriptionResponder.Subscription subs = createSubscription(subscription);
				String subsID = subscription.getConversationId();
				synchronized(subsMap) {
					subsMap.put(subsID, subs);
				}				
				return null;
			} else {
				// Connection has been not established yet, refusing subscription.
				ACLMessage reply = subscription.createReply();
				reply.setPerformative(ACLMessage.REFUSE);
				return reply;
			}
		}
		
		protected ACLMessage handleCancel(ACLMessage cancel)
                throws FailureException {
			// Cancel subscription request received, processing it.
			System.out.println(myAgent.getAID().getName() + " - received cancel message");
			synchronized(subsMap) {
				SubscriptionResponder.Subscription subToRemove = subsMap.remove(cancel.getConversationId());
				if (subToRemove == null) {
					// Agent that requests cancel was not subscribed previously.
					System.out.println(myAgent.getAID().getName() + " - cannot find mapping for cancel message " + cancel.getConversationId());
					ACLMessage reply = cancel.createReply();
					reply.setPerformative(ACLMessage.FAILURE);
					return reply;
				} else {
					// Closing requested subscription.
					subToRemove.close();
					System.out.println(myAgent.getAID().getName() + " - subscription ID " + cancel.getConversationId() + " canceled");
					return null;
				}
			}
		}
	}
	
	/**
	 * Behavior, executed in parallel which reads process values from plant's server
	 * @author jpospiech
	 *
	 */
	private class ReadFromPlant extends OneShotBehaviour {
		ReadFromPlant(Agent a) {
			super(a);
		}
		
		public void action() {
			synchronized(valMap) {
				try {
					// Thread is put to wait, at the beginning there is nothing to connect to.
					valMap.wait();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			String serverOutput;
			String wholeXML = "";
			String eventName = "";
			String varName = "";
			String varVal = "";
			boolean varnamesWritten = false;
			XMLInputFactory xmlReaderFactory = XMLInputFactory.newInstance();
			// For reading we assume that XML structure is fixed which means that <Cluster> is root element.
			// Data is received in following pattern: first is Name element with variable name and next come value element.
			// When </Cluster> is received for the first time we know, that every variable was provided once.
			
			// NOTE currently program is adapted to work with LabView XML Schema, data in LabView is converted
			// to XML using "Flatten to XML" function.
			if (connectionState == ESTABLISHING_CONNECTION) {
				System.out.println(myAgent.getAID().getName() + " - starting executing parallel behaviour");
				try {
					//System.out.println("Starting reading data.");
					// plantReader.readLine() is blocking call
					while ((serverOutput = plantReader.readLine()) != null) {
						// Sadly XMLparser cannot read consecutive XML files from one input stream so end of XML needs
						// to be detected, and whole XML saved as string, which then will be converted into new InputStream
						// that could be read by XMLparser.
						if (serverOutput.trim().contentEquals("</Cluster>")) {
							//System.out.println("Found end of XML");
							// There is explicitly added CRLF to last line so readLine() would be able to read it, it should be trimmed
							// so XML parser would not have any problem (it is problematic enough by itself).
							wholeXML = wholeXML + serverOutput.trim();
							
							InputStream inputStream = new ByteArrayInputStream(wholeXML.getBytes(Charset.forName("UTF-8")));
							
							XMLEventReader xmlReader = xmlReaderFactory.createXMLEventReader(inputStream);
							
							while(xmlReader.hasNext()) {
								XMLEvent event = xmlReader.nextEvent();
								
								if (event.isStartElement()) { 
					                StartElement element = (StartElement) event;
					                eventName = element.getName().toString();
					                //System.out.println("Found start event: " + eventName);
					            }
								
								if (event.isCharacters()) {
									Characters element = (Characters) event;
									switch(eventName) {
									case "Name":
										varName = element.getData();
										//System.out.println("In case name: " + varName);
										break;
									case "Val":
										try {
											double varValDBL = Double.parseDouble(element.getData());
											varVal = String.format("%.2f", varValDBL);
											//System.out.println("In case Val: " + varVal);
											if (!varName.isEmpty()) {
												if (varnamesWritten == false) {
													valMap.put(varName, varVal);
													System.out.println("Added label " + varName);
												} else {
													valMap.replace(varName, varVal);
												}
												varName = ""; //just to force correct pattern
											}
										} catch (NumberFormatException e) {
											System.out.println("Problems occured during double conversion");
										}
										break;
									default:
										break;
									}
								}
								
								if (event.isEndElement()) {
									EndElement element = (EndElement) event;
									//System.out.println("Found end event: " + element.getName().toString());
									if (element.getName().toString().equals("Cluster") && varnamesWritten == false) {
										varnamesWritten = true;
										connectionState = CONNECTION_ESTABLISHED;
										synchronized(valMap) {
											// First read finished, notify all waiting threads, that
											// value names are available
											valMap.notifyAll();
										}
									} else if (element.getName().toString().equals("Cluster") && varnamesWritten == true) {
										StringBuilder messageContent = new StringBuilder();
										ACLMessage subscriptionMessage = new ACLMessage(ACLMessage.INFORM);
										
										// whole XML parsed, sending update to all subscribed agents
										synchronized(valMap) {
											for(Map.Entry<String, String> entry : valMap.entrySet()) {
												messageContent.append(entry.getKey() + ":" + entry.getValue() + ";");
											}
										}
										
										if (messageContent.length() > 0) {
											messageContent.setLength(messageContent.length() - 1);
											subscriptionMessage.setContent(messageContent.toString());
										} else {
											// Something went wrong
											connectionState = ERROR_OCCURED;
											subscriptionMessage.setPerformative(ACLMessage.FAILURE);
										}
										
										synchronized(subsMap) {
											for (Map.Entry<String, SubscriptionResponder.Subscription> subsEntry : subsMap.entrySet()) {
												subsEntry.getValue().notify(subscriptionMessage);
											}
										}
									}
								}
							}
							wholeXML = ""; // Cleaning XML and waiting for a new one
							if (connectionState == ERROR_OCCURED) {
								myAgent.doDelete();
								break;
							}
						} else {
							wholeXML = wholeXML + serverOutput; // if it is not end of XML then just append it
						}
					}	
				} catch (XMLStreamException e) {
					// error in parsing XML
					System.out.println(getAID().getName() + " xml parsing exception occured, closing agent.");
					connectionState = ERROR_OCCURED;
					ACLMessage subscriptionMessage = new ACLMessage(ACLMessage.FAILURE);
					synchronized(subsMap) {
						for (Map.Entry<String, SubscriptionResponder.Subscription> subsEntry : subsMap.entrySet()) {
							subsEntry.getValue().notify(subscriptionMessage);
						}
					}
					myAgent.doDelete();
				} catch (IOException e ) {
					// agent lost connection for some reason.
					System.out.println(getAID().getName() + " unexpected IOException occured, closing agent.");
					connectionState = PLANT_DISCONNECTED;
					ACLMessage subscriptionMessage = new ACLMessage(ACLMessage.FAILURE);
					subscriptionMessage.setContent(PLANT_DISCONNECTED);
					synchronized(subsMap) {
						for (Map.Entry<String, SubscriptionResponder.Subscription> subsEntry : subsMap.entrySet()) {
							subsEntry.getValue().notify(subscriptionMessage);
						}
					}
					myAgent.doDelete();
				}
			} else {
				System.out.println(myAgent.getAID().getName() + " - connectionState was not equal to ESTABLISHING_CONNECTION while starting parallel behaviour");
			}
		}
	}

}

package agentControlSystem;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class TCPClientConnectionGui extends JFrame {
	private GUIAgent myAgent;
	
	private JPanel mainPanel;
	private JTextField IPAddress;
	private JTextField serverPort;
	
	TCPClientConnectionGui(GUIAgent agent){
		super(agent.getLocalName());
		
		myAgent = agent;
		
		mainPanel = new JPanel();
		
		GroupLayout layout = new GroupLayout(mainPanel);
        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);
        mainPanel.setLayout(layout);
        
        
		//getContentPane().setLayout(new GridLayout(0, 1));
		// IP part
		JLabel IPLabel = new JLabel("IP Address: ");
		IPAddress = new JTextField(25);
		// Port part
		JLabel portLabel = new JLabel("Port: ");
		serverPort = new JTextField(10);
		// Connection button
		JButton connectionButton = new JButton("Connect");
		connectionButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent ev) {
				try {
					String IPAddr = IPAddress.getText().trim();
					int port = Integer.parseInt(serverPort.getText().trim());
					myAgent.monitorPlant(IPAddr, port);
				} catch (NullPointerException e) {
					printConnectionError("Empty IP and/or port field.");
				} catch (NumberFormatException e) {
					printConnectionError("Incorrect data type in port field.");
				} catch (Exception e) {
					printConnectionError("Unable to establish connection.");
				}
			}
		});
			
		addWindowListener(new	WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				myAgent.handleConnectionGuiClosing();
			}
		} );
		
		layout.setHorizontalGroup(layout.createParallelGroup(GroupLayout.Alignment.CENTER)
				.addGroup(layout.createSequentialGroup().addComponent(IPLabel).addComponent(IPAddress))
				.addGroup(layout.createSequentialGroup().addComponent(portLabel).addComponent(serverPort))
				.addComponent(connectionButton));
		
		layout.setVerticalGroup(layout.createSequentialGroup()
				.addGroup(layout.createParallelGroup(GroupLayout.Alignment.TRAILING).addComponent(IPLabel).addComponent(IPAddress))
				.addGroup(layout.createParallelGroup(GroupLayout.Alignment.TRAILING).addComponent(portLabel).addComponent(serverPort))
				.addComponent(connectionButton));
		
		getContentPane().add(mainPanel);
		// setResizable(false);
		
	}
	
	// Simply prints connection error.
	public void printConnectionError(final String s) {
		JOptionPane.showMessageDialog(null, s, "Connection error", JOptionPane.ERROR_MESSAGE);
	}
	
	// Shows Gui
	public void showGui() {
		pack();
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		int centerX = (int)screenSize.getWidth() / 2;
		int centerY = (int)screenSize.getHeight() / 2;
		setLocation(centerX - getWidth() / 2, centerY - getHeight() / 2);
		super.setVisible(true);
	}
	
	// Hides Gui (Gui is still usable, it is not deleted!)
	public void hideGui() {
		super.setVisible(false);
	}
	
}

/*
 * ResultsGui
 * 
 * Gui in swing that presents data from plants.
 * 
 * author - Jakub Pośpiech
 */

package agentControlSystem;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

import java.util.HashMap;

public class ResultsGui extends JFrame {
	private GUIAgent myAgent;
	
	private JPanel mainPanel;
	GroupLayout layout;
	GroupLayout.ParallelGroup parallelGroup;
	GroupLayout.SequentialGroup sequentialGroup;
	
	private HashMap<String, JLabel> varMap;
	private HashMap<String, JPanel> panelMap;
	
	ResultsGui(GUIAgent agent) {
		super(agent.getLocalName());
		
		myAgent = agent;
		
		mainPanel = new JPanel();
		varMap = new HashMap<String, JLabel>();
		panelMap = new HashMap<String, JPanel>();
		
        layout = new GroupLayout(mainPanel);
        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);
        mainPanel.setLayout(layout);
        parallelGroup = layout.createParallelGroup(GroupLayout.Alignment.LEADING);
        sequentialGroup = layout.createSequentialGroup();
        
        layout.setHorizontalGroup(sequentialGroup);
        layout.setVerticalGroup(parallelGroup);
        
        JLabel channelIDInfo = new JLabel("Channel ID:");
        JLabel variableValuesInfo = new JLabel("Variable values:");
        
        parallelGroup.addGroup(layout.createSequentialGroup()
        		.addComponent(channelIDInfo)
        		.addComponent(variableValuesInfo));
        sequentialGroup.addGroup(layout.createParallelGroup(GroupLayout.Alignment.TRAILING)
        		.addComponent(channelIDInfo)
        		.addComponent(variableValuesInfo));
        
        getContentPane().add(mainPanel);
	}
	
	/**
	 * Creates label to view every provided variable
	 * @param connectionID - ID of connection, must be unique
	 * @param varName - variable local name
	 * @param panelParallel - parallel layout of panel
	 * @param panelSequentail - sequential layout of panel
	 * @param panelLayout - panel layout
	 * 
	 * @return 1 if label was created successfully, 0 if label already existed
	 */
	private void addLabel(final String connectionID, final String varName, GroupLayout.ParallelGroup panelParallel,
			GroupLayout.SequentialGroup panelSequential, GroupLayout panelLayout) {
		// Labels for all variables
		JLabel varLabel = new JLabel("none");
		JLabel varNameLabel = new JLabel(varName + ": ");
		String fullVarName = connectionID + "_" + varName;
		
		if (!varMap.containsKey(fullVarName.trim())) {
			varMap.put(fullVarName.trim(), varLabel);
			panelParallel.addGroup(panelLayout.createSequentialGroup()
					.addComponent(varNameLabel)
					.addComponent(varLabel));
			panelSequential.addGroup(panelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING)
					.addComponent(varNameLabel)
					.addComponent(varLabel));
		}
	}
	
	/**
	 * Method for scaling GUI, this should be
	 * called only by methods in GUI class.
	 */
	private void scaleGui() {
		pack();
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		int centerX = (int)screenSize.getWidth() / 2;
		int centerY = (int)screenSize.getHeight() / 2;
		setLocation(centerX - getWidth() / 2, centerY - getHeight() / 2);
	}
	
	/**
	 * Method for finishing configuration procedures
	 */
	private void endConfig() {
		addWindowListener(new	WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				myAgent.handleResultGuiClosing();
			}
		} );
	}
	
	/**
	 * Creates label to show variables values from plant
	 * @param connectionID - ID of connection, must be unique
	 * @param showID - ID to show on GUI, may or may not be the same as connectionID
	 * @param varTable - table with names of all variables needed
	 */
	public void createPlantLabels(final String connectionID, final String showID, String[] varTable) {
		
		JPanel plantPanel = new JPanel();
        GroupLayout panelLayout = new GroupLayout(plantPanel);
        panelLayout.setAutoCreateGaps(true);
        panelLayout.setAutoCreateContainerGaps(true);
        plantPanel.setLayout(panelLayout);
        GroupLayout.ParallelGroup panelParallel = panelLayout.createParallelGroup(GroupLayout.Alignment.TRAILING);
        GroupLayout.SequentialGroup panelSequential = panelLayout.createSequentialGroup();
        
        panelLayout.setHorizontalGroup(panelParallel);
        panelLayout.setVerticalGroup(panelSequential);
        
        if (!panelMap.containsKey(connectionID.trim())) {
        	panelMap.put(connectionID.trim(), plantPanel);
        }
        
        JLabel conIDLabel = new JLabel(showID);
		panelParallel.addComponent(conIDLabel);
		panelSequential.addComponent(conIDLabel);
        
		for (int i = 0; i < varTable.length; i++) {
			addLabel(connectionID, varTable[i], panelParallel, panelSequential, panelLayout);
		}
		
		// Stop button 
		JButton stopButton = new JButton("STOP");
		stopButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent ev) {
				myAgent.handleStopCommand(connectionID);
			}
		});
		
		panelParallel.addComponent(stopButton);
		panelSequential.addComponent(stopButton);
		
		parallelGroup.addComponent(plantPanel);
		sequentialGroup.addComponent(plantPanel);
		mainPanel.validate();
		scaleGui();		
	}
			
	/**
	 * Updates variable values on Gui
	 * @param connectionID - ID of connection
	 * @param varName - name of updated variable
	 * @param varVal - new value
	 */
	public void updateValue(final String connectionID, final String varName, final String varVal) {
		final String varKey = connectionID + "_" + varName;
		JLabel varLabel = varMap.get(varKey.trim());
		if (varLabel != null) {
			varLabel.setText(varVal);
		} else {
			System.out.println("Error updating variable values, variable name was not found");
		}
	}
	
	/**
	 * Method that closes labels showing variable values for plant
	 * specified by connectionID.
	 * @param connectionID - connectionID specifying which labels to close
	 */
	public void closePlantLabels(final String connectionID) {
		
		if (panelMap.containsKey(connectionID.trim())) {
			JPanel panelToClose = panelMap.get(connectionID.trim());
			if (panelToClose != null) {
				panelToClose.removeAll();
				mainPanel.remove(panelToClose);
			}
			panelMap.remove(connectionID.trim());
		} else {
			System.out.println("Error deleting plant labels, no panel found.");
		}
		varMap.entrySet().removeIf(entry -> entry.getKey().contains(connectionID.trim()));
		mainPanel.validate();
		scaleGui();	
	}
	
	/**
	 * Method for showing using necessary information.
	 * @param infoText - text that user should see
	 */
	public void showInfoDialog(final String infoText) {
		JOptionPane.showMessageDialog(null, infoText);
	}
	
	/**
	 * Method for showing information about plant disconnection.
	 * @param connectionID - Id of disconnected plant
	 */
	public void showDisconnectionDialog(final String connectionID) {
		JOptionPane.showMessageDialog(null, "Plant " + connectionID + " disconnected.", "Plant disconnected"
				,JOptionPane.WARNING_MESSAGE);
	}
	
	// Shows Gui
	public void showGui() {
		endConfig();
		super.setVisible(true);
	}
	
	// Hides Gui (Gui is still usable, it is not deleted!)
	public void hideGui() {
		super.setVisible(false);
	}
}

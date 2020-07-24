package agentControlSystem;

public class PlantAddress {
	private final String IPAddress;
	private int port;
	public PlantAddress(final String IP, int port_num) {
		this.IPAddress = IP;
		this.port = port_num;
	}
	
	public final String getIP() { return this.IPAddress; }
	public int getPort() { return this.port; }
	
	public boolean isSameAddress(final String IP, int port_num) {
		if (this.port == port_num && IPAddress.equals(IP)) {
			return true;
		} else {
			return false;
		}
	}
	
	public boolean isSameAddress(PlantAddress address) {
		return isSameAddress(address.getIP(), address.getPort());
	}

}

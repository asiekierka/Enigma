package cuchaz.enigma.network;

import cuchaz.enigma.gui.GuiController;
import cuchaz.enigma.network.packet.KickS2CPacket;
import cuchaz.enigma.network.packet.Packet;
import cuchaz.enigma.network.packet.PacketRegistry;
import cuchaz.enigma.network.packet.RemoveMappingS2CPacket;
import cuchaz.enigma.network.packet.RenameS2CPacket;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class EnigmaServer {

	// https://discordapp.com/channels/507304429255393322/566418023372816394/700292322918793347
	public static final int DEFAULT_PORT = 34712;
	public static final int PROTOCOL_VERSION = 0;
	public static final String OWNER_USERNAME = "Owner";
	public static final int CHECKSUM_SIZE = 20;

	private final int port;
	private ServerSocket socket;
	private List<Socket> clients = new CopyOnWriteArrayList<>();
	private Map<Socket, String> usernames = new HashMap<>();
	private Set<Socket> unapprovedClients = new HashSet<>();

	private final byte[] jarChecksum;

	public static final int DUMMY_SYNC_ID = 0;
	private final EntryRemapper mappings;
	private Map<Entry<?>, Integer> syncIds = new HashMap<>();
	private Map<Integer, Entry<?>> inverseSyncIds = new HashMap<>();
	private Map<Integer, Set<Socket>> clientsNeedingConfirmation = new HashMap<>();
	private int nextSyncId = DUMMY_SYNC_ID + 1;

	private static int nextIoId = 0;

	public EnigmaServer(byte[] jarChecksum, EntryRemapper mappings, int port) {
		this.jarChecksum = jarChecksum;
		this.mappings = mappings;
		this.port = port;
	}

	public void start() throws IOException {
		socket = new ServerSocket(port);
		log("Server started on port " + port);
		Thread thread = new Thread(() -> {
			try {
				while (!socket.isClosed()) {
					acceptClient();
				}
			} catch (SocketException e) {
				System.out.println("Server closed");
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		thread.setName("Server client listener");
		thread.setDaemon(true);
		thread.start();
	}

	private void acceptClient() throws IOException {
		Socket client = socket.accept();
		clients.add(client);
		Thread thread = new Thread(() -> {
			try {
				DataInput input = new DataInputStream(client.getInputStream());
				while (true) {
					int packetId;
					try {
						packetId = input.readUnsignedByte();
					} catch (EOFException | SocketException e) {
						break;
					}
					Packet<ServerPacketHandler> packet = PacketRegistry.createC2SPacket(packetId);
					if (packet == null) {
						throw new IOException("Received invalid packet id " + packetId);
					}
					packet.read(input);
					runOnThread(() -> packet.handle(new ServerPacketHandler(client, this)));
				}
			} catch (IOException e) {
				kick(client, e.toString());
				e.printStackTrace();
				return;
			}
			kick(client, "disconnect.disconnected");
		});
		thread.setName("Server I/O thread #" + (nextIoId++));
		thread.setDaemon(true);
		thread.start();
	}

	public synchronized void stop() {
		if (!socket.isClosed()) {
			for (Socket client : clients) {
				kick(client, "disconnect.server_closed");
			}
			try {
				socket.close();
			} catch (IOException e) {
				System.err.println("Failed to close server socket");
				e.printStackTrace();
			}
		}
	}

	public void kick(Socket client, String reason) {
		sendPacket(client, new KickS2CPacket(reason));

		clients.remove(client);
		clientsNeedingConfirmation.values().removeIf(list -> {
			list.remove(client);
			return list.isEmpty();
		});
		String username = usernames.remove(client);
		try {
			client.close();
		} catch (IOException e) {
			System.err.println("Failed to close server client socket");
			e.printStackTrace();
		}

		System.out.println("Kicked " + username + " because " + reason);
	}

	public boolean isUsernameTaken(String username) {
		return usernames.containsValue(username);
	}

	public void setUsername(Socket client, String username) {
		usernames.put(client, username);
	}

	public String getUsername(Socket client) {
		return usernames.get(client);
	}

	public void sendPacket(Socket client, Packet<GuiController> packet) {
		if (!client.isClosed()) {
			int packetId = PacketRegistry.getS2CId(packet);
			try {
				DataOutput output = new DataOutputStream(client.getOutputStream());
				output.writeByte(packetId);
				packet.write(output);
			} catch (IOException e) {
				if (!(packet instanceof KickS2CPacket)) {
					kick(client, e.toString());
					e.printStackTrace();
				}
			}
		}
	}

	public void sendToAll(Packet<GuiController> packet) {
		for (Socket client : clients) {
			sendPacket(client, packet);
		}
	}

	public void sendToAllExcept(Socket excluded, Packet<GuiController> packet) {
		for (Socket client : clients) {
			if (client != excluded) {
				sendPacket(client, packet);
			}
		}
	}

	public boolean canModifyEntry(Socket client, Entry<?> entry) {
		if (unapprovedClients.contains(client)) {
			return false;
		}

		Integer syncId = syncIds.get(entry);
		if (syncId == null) {
			return true;
		}
		Set<Socket> clients = clientsNeedingConfirmation.get(syncId);
		return clients == null || !clients.contains(client);
	}

	public int lockEntry(Socket exception, Entry<?> entry) {
		int syncId = nextSyncId;
		nextSyncId++;
		// sync id is sent as an unsigned short, can't have more than 65536
		if (nextSyncId == 65536) {
			nextSyncId = DUMMY_SYNC_ID + 1;
		}
		Integer oldSyncId = syncIds.get(entry);
		if (oldSyncId != null) {
			clientsNeedingConfirmation.remove(oldSyncId);
		}
		syncIds.put(entry, syncId);
		inverseSyncIds.put(syncId, entry);
		Set<Socket> clients = new HashSet<>(this.clients);
		clients.remove(exception);
		clientsNeedingConfirmation.put(syncId, clients);
		return syncId;
	}

	public void confirmChange(Socket client, int syncId) {
		if (usernames.containsKey(client)) {
			unapprovedClients.remove(client);
		}

		Set<Socket> clients = clientsNeedingConfirmation.get(syncId);
		if (clients != null) {
			clients.remove(client);
			if (clients.isEmpty()) {
				clientsNeedingConfirmation.remove(syncId);
				syncIds.remove(inverseSyncIds.remove(syncId));
			}
		}
	}

	public void sendCorrectMapping(Socket client, Entry<?> entry, boolean refreshClassTree) {
		EntryMapping oldMapping = mappings.getDeobfMapping(entry);
		String oldName = oldMapping == null ? null : oldMapping.getTargetName();
		if (oldName == null) {
			sendPacket(client, new RemoveMappingS2CPacket(DUMMY_SYNC_ID, entry));
		} else {
			sendPacket(client, new RenameS2CPacket(0, entry, oldName, refreshClassTree));
		}
	}

	protected abstract void runOnThread(Runnable task);

	public void log(String message) {
		System.out.println(message);
	}

	protected boolean isRunning() {
		return !socket.isClosed();
	}

	public byte[] getJarChecksum() {
		return jarChecksum;
	}

	public EntryRemapper getMappings() {
		return mappings;
	}

}

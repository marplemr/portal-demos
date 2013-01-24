package ch.rasc.portaldemos.echat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.xml.bind.DatatypeConverter;

import net.sf.uadetector.UserAgent;
import net.sf.uadetector.UserAgentFamily;
import net.sf.uadetector.UserAgentStringParser;
import net.sf.uadetector.service.UADetectorServiceFactory;

import org.codehaus.jackson.map.ObjectMapper;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.flowersinthesand.portal.Bean;
import com.github.flowersinthesand.portal.Data;
import com.github.flowersinthesand.portal.Fn;
import com.github.flowersinthesand.portal.On;
import com.github.flowersinthesand.portal.Reply;
import com.github.flowersinthesand.portal.Room;
import com.github.flowersinthesand.portal.Socket;
import com.github.flowersinthesand.portal.Wire;
import com.google.common.collect.Maps;

@Bean
public class EnhancedChatHandler {

	private static final String DATA_IMAGE = "data:image/png;base64,";

	private final static Logger logger = LoggerFactory.getLogger(EnhancedChatHandler.class);

	private final static ObjectMapper mapper = new ObjectMapper();

	private final UserAgentStringParser parser = UADetectorServiceFactory.getResourceModuleParser();

	private final Map<String, UserConnection> socketIdToUserMap = Maps.newConcurrentMap();

	private final Map<String, Socket> usernameToSocketMap = Maps.newConcurrentMap();

	@Wire("echat")
	Room room;

	@On.close
	public void close(Socket socket) {
		disconnect(socket, null);
	}

	@On("disconnect")
	public void disconnect(Socket socket, @Reply Fn.Callback reply) {
		UserConnection uc = socketIdToUserMap.remove(socket.param("id"));
		if (uc != null) {
			room.send("disconnected", uc);
			usernameToSocketMap.remove(uc.getUsername());
		}

		if (reply != null) {
			reply.call();
		}
	}

	@On("connect")
	public void connect(Socket socket, @Data UserConnection newUser, @Reply Fn.Callback reply) {

		UserAgent ua = parser.parse(newUser.getBrowser());
		if (ua != null) {
			newUser.setBrowser(ua.getName() + " " + ua.getVersionNumber().getMajor());
			if (ua.getFamily() == UserAgentFamily.CHROME) {
				newUser.setSupportsWebRTC(true);
			}
		}
		socketIdToUserMap.put(socket.param("id"), newUser);
		usernameToSocketMap.put(newUser.getUsername(), socket);

		room.send("connected", newUser);
		reply.call();
	}

	@On.open
	public void open(Socket socket) {
		room.add(socket);
		socket.send("connectedUsers", socketIdToUserMap.values());
	}

	@On.message
	public void message(@Data ChatMessage message) {
		room.send("message", message);
	}

	@On("sendSdp")
	public void sendSdp(@Data Map<String, Object> offerObject) {
		String toUsername = (String) offerObject.get("toUsername");
		Socket peerSocket = usernameToSocketMap.get(toUsername);
		if (peerSocket != null) {
			peerSocket.send("receiveSdp", offerObject);
		}

		if (logger.isDebugEnabled()) {
			try {
				logger.debug(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(offerObject));
			} catch (IOException e) {
				// ignore this
			}
		}
	}

	@On("sendIceCandidate")
	public void sendIceCandidate(@Data Map<String, Object> candidate) {
		String toUsername = (String) candidate.get("toUsername");
		Socket peerSocket = usernameToSocketMap.get(toUsername);
		if (peerSocket != null) {
			peerSocket.send("receiveIceCandidate", candidate);
		}

		if (logger.isDebugEnabled()) {
			try {
				logger.debug(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(candidate));
			} catch (IOException e) {
				// ignore this
			}
		}
	}

	@On("snapshot")
	public void snapshot(Socket socket, @Data String image) {
		UserConnection uc = socketIdToUserMap.get(socket.param("id"));
		if (uc != null && image.startsWith(DATA_IMAGE)) {
			try {
				byte[] imageBytes = DatatypeConverter.parseBase64Binary(image.substring(DATA_IMAGE.length()));
				String resizedImageDataURL = resize(imageBytes);
				uc.setImage(resizedImageDataURL);
				room.send("snapshot", uc);
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
	}
	
	@On("hangup")
	public void hangup(@Data String toUser) {
		Socket toUserSocket = usernameToSocketMap.get(toUser);
		if (toUserSocket != null) {
			toUserSocket.send("hangup");
		}
	}

	private static String resize(byte[] imageData) throws IOException {
		try (ByteArrayInputStream bis = new ByteArrayInputStream(imageData);
				ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
			BufferedImage image = ImageIO.read(bis);

			BufferedImage resizedImage = Scalr.resize(image, Scalr.Method.AUTOMATIC, Scalr.Mode.AUTOMATIC, 40,
					Scalr.OP_ANTIALIAS);
			ImageIO.write(resizedImage, "png", bos);
			return DATA_IMAGE + DatatypeConverter.printBase64Binary(bos.toByteArray());
		}
	}
}

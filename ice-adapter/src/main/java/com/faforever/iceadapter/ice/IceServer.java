package com.faforever.iceadapter.ice;

import com.faforever.iceadapter.IceAdapter;
import com.faforever.iceadapter.telemetry.CoturnServer;
import com.faforever.iceadapter.util.PingUtil;
import kotlin.Pair;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Data
@Slf4j
public class IceServer {
    private static final String STUN = "stun";
    private static final String TURN = "turn";
    private static final String TURNS = "turns";

    private List<TransportAddress> stunAddresses = new ArrayList<>();
    private List<TransportAddress> turnAddresses = new ArrayList<>();
    private String turnUsername = "";
    private String turnCredential = "";
    private CompletableFuture<OptionalDouble> roundTripTime = CompletableFuture.completedFuture(OptionalDouble.empty());

    public static final Pattern urlPattern = Pattern.compile(
            "(?<protocol>stun|turn|turns):(?<host>(\\w|\\.)+)(:(?<port>\\d+))?(\\?transport=(?<transport>(tcp|udp)))?");

    public boolean hasAcceptableLatency() {
        OptionalDouble rtt = this.getRoundTripTime().join();
        return rtt.isEmpty() || rtt.getAsDouble() < IceAdapter.getAcceptableLatency();
    }

    public static Pair<List<IceServer>, Set<CoturnServer>> mapperFromMap(List<Map<String, Object>> iceServersData) {
        List<IceServer> iceServers = new ArrayList<>();

        Set<CoturnServer> coturnServers = new HashSet<>();

        for (Map<String, Object> iceServerData : iceServersData) {
            IceServer iceServer = new IceServer();

            if (iceServerData.containsKey("username")) {
                iceServer.setTurnUsername((String) iceServerData.get("username"));
            }
            if (iceServerData.containsKey("credential")) {
                iceServer.setTurnCredential((String) iceServerData.get("credential"));
            }

            if (iceServerData.containsKey("urls")) {
                List<String> urls;
                Object urlsData = iceServerData.get("urls");
                if (urlsData instanceof List) {
                    urls = (List<String>) urlsData;
                } else {
                    urls = Collections.singletonList((String) iceServerData.get("url"));
                }

                urls.stream()
                        .map(stringUrl -> {
                            try {
                                return new URI(stringUrl);
                            } catch (Exception e) {
                                log.warn("Invalid ICE server URI: {}", stringUrl);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .forEach(uri -> {
                            String host = uri.getHost();
                            int port = uri.getPort() == -1 ? 3478 : uri.getPort();
                            Transport transport = Optional.ofNullable(uri.getQuery()).stream()
                                    .flatMap(query -> Arrays.stream(query.split("&")))
                                    .map(param -> param.split("="))
                                    .filter(param -> param.length == 2)
                                    .filter(param -> param[0].equals("transport"))
                                    .map(param -> param[1])
                                    .map(Transport::parse)
                                    .findFirst()
                                    .orElse(Transport.UDP);

                            TransportAddress address = new TransportAddress(host, port, transport);
                            switch (uri.getScheme()) {
                                case STUN -> iceServer.getStunAddresses().add(address);
                                case TURNS, TURN -> iceServer.getTurnAddresses().add(address);
                                default -> log.warn("Invalid ICE server protocol: {}", uri);
                            }

                            if (IceAdapter.getPingCount() > 0) {
                                iceServer.setRoundTripTime(PingUtil.getLatency(host));
                            }

                            coturnServers.add(new CoturnServer("n/a", host, port, null));
                        });
            }

            iceServers.add(iceServer);
        }

        return new Pair<>(iceServers, coturnServers);
    }
}

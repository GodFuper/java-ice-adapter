package com.faforever.iceadapter.util;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidatesMessage;
import com.faforever.iceadapter.ice.peer.Peer;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CandidateUtil {
    public static int candidateIDFactory = 0;

    private static CandidatePacket createCandidatePacket(Agent agent, LocalCandidate localCandidate) {
        String relAddr = null;
        int relPort = 0;

        if (localCandidate.getRelatedAddress() != null) {
            relAddr = localCandidate.getRelatedAddress().getHostAddress();
            relPort = localCandidate.getRelatedAddress().getPort();
        }

        return new CandidatePacket(
                localCandidate.getFoundation(),
                localCandidate.getTransportAddress().getTransport().toString(),
                localCandidate.getPriority(),
                localCandidate.getTransportAddress().getHostAddress(),
                localCandidate.getTransportAddress().getPort(),
                localCandidate.getType(),
                agent.getGeneration(),
                String.valueOf(candidateIDFactory++),
                relAddr,
                relPort);
    }

    public static CandidatesMessage packCandidates(
            int srcId,
            int destId,
            Agent agent,
            Component component,
            boolean allowHost,
            boolean allowReflexive,
            boolean allowRelay) {
        final List<CandidatePacket> candidatePackets = new ArrayList<>();

        List<CandidatePacket> prePackets = component.getLocalCandidates().stream()
                .map(candidate -> createCandidatePacket(agent, candidate))
                .toList();
        for (CandidatePacket packet : prePackets) {
            if (isAllowedCandidate(allowHost, allowReflexive, allowRelay, packet.type())) {
                candidatePackets.add(packet);
            }
        }
        Collections.sort(candidatePackets);

        return new CandidatesMessage(srcId, destId, agent.getLocalPassword(), agent.getLocalUfrag(), candidatePackets);
    }

    public static void unpackCandidates(
            Peer peer,
            CandidatesMessage remoteCandidatesMessage,
            Agent agent,
            Component component,
            IceMediaStream mediaStream,
            boolean allowHost,
            boolean allowReflexive,
            boolean allowRelay) {
        // Set candidates
        String ufrag = remoteCandidatesMessage.ufrag();
        mediaStream.setRemotePassword(remoteCandidatesMessage.password());
        mediaStream.setRemoteUfrag(ufrag);

        remoteCandidatesMessage.candidates().stream()
                .sorted() // just in case some ICE adapter implementation did not sort it yet
                .forEach(remoteCandidatePacket -> {
                    if (remoteCandidatePacket.generation() == agent.getGeneration()
                            && remoteCandidatePacket.ip() != null
                            && remoteCandidatePacket.port() > 0) {

                        TransportAddress mainAddress = new TransportAddress(
                                remoteCandidatePacket.ip(),
                                remoteCandidatePacket.port(),
                                Transport.parse(remoteCandidatePacket.protocol().toLowerCase()));

                        RemoteCandidate relatedCandidate = null;
                        if (remoteCandidatePacket.relAddr() != null && remoteCandidatePacket.relPort() > 0) {
                            TransportAddress relatedAddr = new TransportAddress(
                                    remoteCandidatePacket.relAddr(),
                                    remoteCandidatePacket.relPort(),
                                    Transport.parse(
                                            remoteCandidatePacket.protocol().toLowerCase()));
                            relatedCandidate = component.findRemoteCandidate(relatedAddr);
                        }

                        RemoteCandidate remoteCandidate = new RemoteCandidate(
                                mainAddress,
                                component,
                                remoteCandidatePacket
                                        .type(), // Expected to not return LOCAL or STUN (old names for host and srflx)
                                remoteCandidatePacket.foundation(),
                                remoteCandidatePacket.priority(),
                                relatedCandidate,
                                ufrag);

                        if (isAllowedCandidate(allowHost, allowReflexive, allowRelay, remoteCandidate.getType())) {
                            component.addRemoteCandidate(remoteCandidate);
                        }
                    }
                });
    }

    public static String infoCandidate(CandidatePair pair) {
        if (pair == null) {
            return null;
        }
        return """
                Local Candidate:
                  Type: %s
                  Transport: %s
                  Address: %s:%d
                  Priority: %d
                  Foundation: %s
                
                Remote Candidate:
                  Type: %s
                  Transport: %s
                  Address: %s:%d
                  Priority: %d
                  Foundation: %s
                
                Priority: %d
                Nominated: %s
                State: %s
                """
                .formatted(
                        pair.getLocalCandidate().getType(),
                        pair.getLocalCandidate().getTransport(),
                        pair.getLocalCandidate().getTransportAddress().getHostAddress(),
                        pair.getLocalCandidate().getTransportAddress().getPort(),
                        pair.getLocalCandidate().getPriority(),
                        pair.getLocalCandidate().getFoundation(),
                        pair.getRemoteCandidate().getType(),
                        pair.getLocalCandidate().getTransport(),
                        pair.getRemoteCandidate().getTransportAddress().getHostAddress(),
                        pair.getRemoteCandidate().getTransportAddress().getPort(),
                        pair.getRemoteCandidate().getPriority(),
                        pair.getRemoteCandidate().getFoundation(),
                        pair.getPriority(),
                        pair.isNominated(),
                        pair.getState());
    }

    private static boolean isAllowedCandidate(
            boolean allowHost, boolean allowReflexive, boolean allowRelay, CandidateType candidateType) {
        // Candidate types LOCAL and STUN can never occur as they are deprecated and not used
        boolean isAllowedHostCandidate = allowHost && candidateType == CandidateType.HOST_CANDIDATE;
        boolean isAllowedReflexiveCandidate = allowReflexive
                && (candidateType == CandidateType.SERVER_REFLEXIVE_CANDIDATE
                || candidateType == CandidateType.PEER_REFLEXIVE_CANDIDATE);
        boolean isAllowedRelayCandidate = allowRelay && candidateType == CandidateType.RELAYED_CANDIDATE;

        return isAllowedHostCandidate || isAllowedReflexiveCandidate || isAllowedRelayCandidate;
    }

    /**
     * Converts a WebRTC candidate line (e.g. "candidate:423499824 1 udp 2113937151 192.168.1.100 50002 typ host...")
     * into a {@link CandidatePacket}.
     */
    public static CandidatePacket webRtcCandidateToPacket(String candidateStr) {
        if (candidateStr == null) {
            return null;
        }
        String line = candidateStr.trim();
        if (line.startsWith("a=")) {
            line = line.substring(2).trim();
        }
        if (!line.startsWith("candidate:")) {
            return null;
        }
        String rest = line.substring("candidate:".length()).trim();
        String[] parts = rest.split("\\s+");
        if (parts.length < 8) {
            return null;
        }
        try {
            String foundation = parts[0];
            String protocol = parts[2].toLowerCase();
            long priority = Long.parseLong(parts[3]);
            String ip = parts[4];
            int port = Integer.parseInt(parts[5]);
            String typeStr = parts[7].toLowerCase();
            CandidateType type = switch (typeStr) {
                case "host" -> CandidateType.HOST_CANDIDATE;
                case "srflx" -> CandidateType.SERVER_REFLEXIVE_CANDIDATE;
                case "prflx" -> CandidateType.PEER_REFLEXIVE_CANDIDATE;
                case "relay" -> CandidateType.RELAYED_CANDIDATE;
                default -> CandidateType.HOST_CANDIDATE;
            };

            String relAddr = null;
            int relPort = 0;
            int generation = 0;

            for (int i = 8; i < parts.length - 1; i++) {
                if ("raddr".equalsIgnoreCase(parts[i])) {
                    relAddr = parts[i + 1];
                } else if ("rport".equalsIgnoreCase(parts[i])) {
                    try {
                        relPort = Integer.parseInt(parts[i + 1]);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("generation".equalsIgnoreCase(parts[i])) {
                    try {
                        generation = Integer.parseInt(parts[i + 1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            return new CandidatePacket(
                    foundation,
                    protocol,
                    priority,
                    ip,
                    port,
                    type,
                    generation,
                    String.valueOf(candidateIDFactory++),
                    relAddr,
                    relPort);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts a {@link CandidatePacket} back to a standard WebRTC candidate SDP line.
     */
    public static String candidatePacketToWebRtcString(CandidatePacket cp) {
        if (cp == null) {
            return null;
        }
        String typeStr = switch (cp.type()) {
            case HOST_CANDIDATE, LOCAL_CANDIDATE -> "host";
            case SERVER_REFLEXIVE_CANDIDATE, STUN_CANDIDATE -> "srflx";
            case PEER_REFLEXIVE_CANDIDATE -> "prflx";
            case RELAYED_CANDIDATE -> "relay";
            default -> "host";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("candidate:").append(cp.foundation())
                .append(" 1 ")
                .append(cp.protocol().toLowerCase())
                .append(" ")
                .append(cp.priority())
                .append(" ")
                .append(cp.ip())
                .append(" ")
                .append(cp.port())
                .append(" typ ")
                .append(typeStr);
        if (cp.relAddr() != null && cp.relPort() > 0) {
            sb.append(" raddr ").append(cp.relAddr()).append(" rport ").append(cp.relPort());
        }
        sb.append(" generation ").append(cp.generation());
        return sb.toString();
    }
}

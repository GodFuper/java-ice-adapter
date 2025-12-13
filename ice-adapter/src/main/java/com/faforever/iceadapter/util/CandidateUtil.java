package com.faforever.iceadapter.util;

import com.faforever.iceadapter.ice.CandidatePacket;
import com.faforever.iceadapter.ice.CandidatesMessage;
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

        if (!agent.isControlling()) {
            List<CandidatePacket> prePackets = component.getLocalCandidates()
                    .stream()
                    .map(candidate -> createCandidatePacket(agent, candidate))
                    .toList();
            for (CandidatePacket packet : prePackets) {
                if (isAllowedCandidate(allowHost, allowReflexive, allowRelay, packet.type())) {
                    candidatePackets.add(packet);
                }
            }
            Collections.sort(candidatePackets);
        }

        return new CandidatesMessage(srcId, destId, agent.getLocalPassword(), agent.getLocalUfrag(), candidatePackets);
    }

    public static void unpackCandidates(
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

        //Optimize. Maybe x2 faster
        if (!agent.isControlling()) {
            return;
        }

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
                                remoteCandidatePacket.type(), // Expected to not return LOCAL or STUN (old names for host and srflx)
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

    private static boolean isAllowedCandidate(boolean allowHost,
                                              boolean allowReflexive,
                                              boolean allowRelay,
                                              CandidateType candidateType) {
        // Candidate types LOCAL and STUN can never occur as they are deprecated and not used
        boolean isAllowedHostCandidate = allowHost && candidateType == CandidateType.HOST_CANDIDATE;
        boolean isAllowedReflexiveCandidate = allowReflexive
                && (candidateType == CandidateType.SERVER_REFLEXIVE_CANDIDATE
                || candidateType == CandidateType.PEER_REFLEXIVE_CANDIDATE);
        boolean isAllowedRelayCandidate = allowRelay && candidateType == CandidateType.RELAYED_CANDIDATE;

        return isAllowedHostCandidate || isAllowedReflexiveCandidate || isAllowedRelayCandidate;
    }
}

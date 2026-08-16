package com.faforever.iceadapter.util;

import com.faforever.iceadapter.dto.serializer.CandidateTypeSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import com.github.luben.zstd.Zstd;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.ice4j.ice.CandidateType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@UtilityClass
@Slf4j
public class ObjectMapperUtil {

    private final ObjectMapper mapperJson = new ObjectMapper()
            .registerModule(new SimpleModule()
                    .addSerializer(CandidateType.class, new CandidateTypeSerializer.Serializer())
                    .addDeserializer(CandidateType.class, new CandidateTypeSerializer.Deserializer()))
            .registerModule(new BlackbirdModule());

    public <T> T fromBytes(byte[] data, Class<T> clazz) {
        String json = new String(data, StandardCharsets.UTF_8);
        return fromJson(json, clazz);
    }

    public String toJson(Object object) {
        try {
            return mapperJson.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Cant serialize object to bytes {}", object, e);
            return "";
        }
    }

    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return mapperJson.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Cant deserialize string {} to object {}", json, clazz, e);
            return null;
        }
    }

    public byte[] toBytes(Object object) {
        return toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toBytesAndAddFirstByte(byte first, Object object, boolean compress) {
        byte[] bytes = toBytes(object);
        if (compress) {
            bytes = Zstd.compress(bytes);
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 + bytes.length);
        buffer.put(first);
        buffer.put(bytes);
        return buffer.array();
    }

    public <T> T fromBytesAndWithOutFirstByte(byte[] bytes, Class<T> clazz, boolean decompress) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(1);
        byte[] objBytes = new byte[buffer.remaining()];
        buffer.get(objBytes);

        if (decompress) {
            objBytes = Zstd.decompress(objBytes);
        }
        return fromBytes(objBytes, clazz);
    }

    //    public static void main(String[] args) throws Exception {
    //        String json =
    // "{\"type\":\"rpc_message_from_client\",\"remoteId\":0,\"message\":{\"srcId\":0,\"destId\":1,\"password\":\"5tq706eguiqjvhask71k8e153l\",\"ufrag\":\"924hq1jp929p5q\",\"candidates\":[{\"foundation\":\"1\",\"protocol\":\"udp\",\"priority\":2130706431,\"ip\":\"fe80:0:0:0:eb99:b947:b3e4:63d7\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"33\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"2\",\"protocol\":\"udp\",\"priority\":2130706431,\"ip\":\"fdfd:0:0:0:0:0:1a63:324c\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"34\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"5\",\"protocol\":\"udp\",\"priority\":2113939711,\"ip\":\"fdaa:147e:a324:0:ac22:f777:d75c:4c67\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"35\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"6\",\"protocol\":\"udp\",\"priority\":2113939711,\"ip\":\"fdaa:147e:a324:0:d08:8f3d:8585:655f\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"36\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"7\",\"protocol\":\"udp\",\"priority\":2113939711,\"ip\":\"fdaa:147e:a324:0:0:0:0:ccd\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"37\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"10\",\"protocol\":\"udp\",\"priority\":2113939711,\"ip\":\"2001:0:14c9:d804:281a:1449:a68e:75e0\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"38\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"4\",\"protocol\":\"udp\",\"priority\":2113937151,\"ip\":\"fe80:0:0:0:c317:46d2:2b32:8112\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"39\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"9\",\"protocol\":\"udp\",\"priority\":2113937151,\"ip\":\"fe80:0:0:0:281a:1449:a68e:75e0\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"40\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"3\",\"protocol\":\"udp\",\"priority\":2113932031,\"ip\":\"26.99.50.76\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"41\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"8\",\"protocol\":\"udp\",\"priority\":2113932031,\"ip\":\"192.168.1.198\",\"port\":7072,\"type\":\"HOST_CANDIDATE\",\"generation\":0,\"id\":\"42\",\"relAddr\":null,\"relPort\":0},{\"foundation\":\"11\",\"protocol\":\"udp\",\"priority\":1677724415,\"ip\":\"89.113.138.31\",\"port\":51878,\"type\":\"SERVER_REFLEXIVE_CANDIDATE\",\"generation\":0,\"id\":\"43\",\"relAddr\":\"192.168.1.198\",\"relPort\":7072}],\"version\":2}}";
    //
    //        var t3 = json.getBytes(StandardCharsets.UTF_8);
    //        var zt3 = Zstd.compress(t3);
    //        var dzt3 = Zstd.decompress(zt3);
    //        String afterJson = new String(dzt3, StandardCharsets.UTF_8);
    //        System.out.println(Objects.equals(json, afterJson));
    //        System.out.println(t3.length);
    //        System.out.println(zt3.length);
    //
    //        RpcMessageFromClientPeerCommand command = fromJson(json, RpcMessageFromClientPeerCommand.class);
    //
    //        System.out.println(mapperJson.writeValueAsBytes(command).length);
    //        System.out.println(fromBytes(mapperJson.writeValueAsBytes(command),
    // RpcMessageFromClientPeerCommand.class));
    //        System.out.println(fromBytes(toBytes(command), RpcMessageFromClientPeerCommand.class));
    //        System.out.println(toBytes(command).length);
    //        byte first = COMMAND_CLIENT;
    //        var t1 = toBytesAndAddFirstByte(first, command, true);
    //        System.out.println(t1.length);
    //
    //        var t2 = fromBytesAndWithOutFirstByte(t1, CommandBase.class, true);
    //        System.out.println(t2);
    //
    //
    //        var time1 = Instant.now();
    //        for (int i = 0; i < 100000; i++) {
    //            var tmp = toBytesAndAddFirstByte(first, command, true);
    //            var result = fromBytesAndWithOutFirstByte(tmp, CommandBase.class, true);
    //        }
    //        var time2 = Instant.now();
    //
    //        System.out.println(Duration.between(time1, time2).toMillis());
    //    }
}

package io.stellasora.server.protocol;

import com.google.protobuf.AnyProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DescriptorRegistry {
    private final Map<String, Descriptors.FileDescriptor> files;
    private final Map<String, Descriptors.Descriptor> messages;

    private DescriptorRegistry(
            Map<String, Descriptors.FileDescriptor> files,
            Map<String, Descriptors.Descriptor> messages) {
        this.files = Map.copyOf(files);
        this.messages = Map.copyOf(messages);
    }

    public static DescriptorRegistry load(Path descriptorSetPath) throws ProtocolException {
        try {
            return from(FileDescriptorSet.parseFrom(Files.readAllBytes(descriptorSetPath)));
        } catch (IOException exception) {
            throw new ProtocolException(
                    "unable to read descriptor set: " + descriptorSetPath, exception);
        }
    }

    public static DescriptorRegistry from(FileDescriptorSet descriptorSet)
            throws ProtocolException {
        Map<String, Descriptors.FileDescriptor> built = new LinkedHashMap<>();
        built.put(AnyProto.getDescriptor().getName(), AnyProto.getDescriptor());

        Map<String, FileDescriptorProto> pending = new LinkedHashMap<>();
        for (FileDescriptorProto file : descriptorSet.getFileList()) {
            FileDescriptorProto previous = pending.put(file.getName(), file);
            if (previous != null) {
                throw new ProtocolException("duplicate descriptor file: " + file.getName());
            }
        }

        boolean progress;
        do {
            progress = false;
            Iterator<Map.Entry<String, FileDescriptorProto>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, FileDescriptorProto> entry = iterator.next();
                FileDescriptorProto file = entry.getValue();
                if (!built.keySet().containsAll(file.getDependencyList())) {
                    continue;
                }
                Descriptors.FileDescriptor[] dependencies = file.getDependencyList().stream()
                        .map(built::get)
                        .toArray(Descriptors.FileDescriptor[]::new);
                try {
                    Descriptors.FileDescriptor descriptor =
                            Descriptors.FileDescriptor.buildFrom(file, dependencies);
                    built.put(entry.getKey(), descriptor);
                    iterator.remove();
                    progress = true;
                } catch (Descriptors.DescriptorValidationException exception) {
                    throw new ProtocolException(
                            "invalid descriptor file " + entry.getKey(), exception);
                }
            }
        } while (progress && !pending.isEmpty());

        if (!pending.isEmpty()) {
            Map<String, Set<String>> missing = new LinkedHashMap<>();
            for (FileDescriptorProto file : pending.values()) {
                Set<String> dependencies = new LinkedHashSet<>(file.getDependencyList());
                dependencies.removeAll(built.keySet());
                missing.put(file.getName(), dependencies);
            }
            throw new ProtocolException("unresolved descriptor dependencies: " + missing);
        }

        Map<String, Descriptors.Descriptor> messages = new HashMap<>();
        for (Descriptors.FileDescriptor file : built.values()) {
            for (Descriptors.Descriptor message : file.getMessageTypes()) {
                registerMessage(message, messages);
            }
        }
        return new DescriptorRegistry(built, messages);
    }

    public Descriptors.Descriptor requireMessage(String fullName) throws ProtocolException {
        Descriptors.Descriptor descriptor = messages.get(fullName);
        if (descriptor == null) {
            throw new ProtocolException("protobuf message is not in the descriptor set: " + fullName);
        }
        return descriptor;
    }

    public DynamicMessage parse(String fullName, byte[] payload) throws ProtocolException {
        try {
            return DynamicMessage.parseFrom(requireMessage(fullName), payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new ProtocolException("invalid protobuf payload for " + fullName, exception);
        }
    }

    public DynamicMessage.Builder builder(String fullName) throws ProtocolException {
        return DynamicMessage.newBuilder(requireMessage(fullName));
    }

    public DynamicMessage empty(String fullName) throws ProtocolException {
        return builder(fullName).build();
    }

    public DynamicMessage presentSingularMessages(String fullName, int maxDepth)
            throws ProtocolException {
        DynamicMessage.Builder builder = builder(fullName);
        populateSingularMessages(builder, maxDepth, new LinkedHashSet<>());
        return builder.build();
    }

    public Descriptors.FieldDescriptor findField(
            Descriptors.Descriptor descriptor, String requestedName) {
        Descriptors.FieldDescriptor exact = descriptor.findFieldByName(requestedName);
        if (exact != null) {
            return exact;
        }
        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            if (field.getName().equalsIgnoreCase(requestedName)
                    || field.getJsonName().equalsIgnoreCase(requestedName)) {
                return field;
            }
        }
        return null;
    }

    public void setIfPresent(DynamicMessage.Builder builder, String fieldName, Object value)
            throws ProtocolException {
        Descriptors.FieldDescriptor field = findField(builder.getDescriptorForType(), fieldName);
        if (field == null) {
            return;
        }
        try {
            builder.setField(field, value);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException(
                    "invalid value for " + builder.getDescriptorForType().getFullName() + "." + fieldName,
                    exception);
        }
    }

    public byte[] bytesField(DynamicMessage message, String fieldName) throws ProtocolException {
        Descriptors.FieldDescriptor field = findField(message.getDescriptorForType(), fieldName);
        if (field == null) {
            throw new ProtocolException(
                    "missing field " + message.getDescriptorForType().getFullName() + "." + fieldName);
        }
        Object value = message.getField(field);
        if (!(value instanceof com.google.protobuf.ByteString bytes)) {
            throw new ProtocolException("field is not bytes: " + field.getFullName());
        }
        return bytes.toByteArray();
    }

    public int fileCount() {
        return files.size();
    }

    public int messageCount() {
        return messages.size();
    }

    public Collection<String> messageNames() {
        return List.copyOf(messages.keySet());
    }

    private static void registerMessage(
            Descriptors.Descriptor descriptor,
            Map<String, Descriptors.Descriptor> destination)
            throws ProtocolException {
        Descriptors.Descriptor previous = destination.put(descriptor.getFullName(), descriptor);
        if (previous != null && previous != descriptor) {
            throw new ProtocolException("duplicate protobuf message: " + descriptor.getFullName());
        }
        for (Descriptors.Descriptor nested : descriptor.getNestedTypes()) {
            registerMessage(nested, destination);
        }
    }

    private static void populateSingularMessages(
            DynamicMessage.Builder builder, int depth, Set<String> stack) {
        if (depth <= 0) {
            return;
        }
        String name = builder.getDescriptorForType().getFullName();
        if (!stack.add(name)) {
            return;
        }
        for (Descriptors.FieldDescriptor field : builder.getDescriptorForType().getFields()) {
            if (field.isRepeated()
                    || field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            DynamicMessage.Builder child = DynamicMessage.newBuilder(field.getMessageType());
            populateSingularMessages(child, depth - 1, stack);
            builder.setField(field, child.build());
        }
        stack.remove(name);
    }
}

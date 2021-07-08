package cuchaz.enigma.translation.mapping.serde.rgs;

import com.google.common.base.Charsets;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.MappingPair;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.serde.MappingsReader;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.mapping.tree.HashEntryTree;
import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public enum RGSReader implements MappingsReader {
    INSTANCE;

    @Override
    public EntryTree<EntryMapping> read(Path path, ProgressListener progress, MappingSaveParameters saveParameters) throws MappingParseException, IOException {
        return read(path, Files.readAllLines(path, Charsets.UTF_8), progress);
    }

    private EntryTree<EntryMapping> read(Path path, List<String> lines, ProgressListener progress) throws MappingParseException {
        EntryTree<EntryMapping> mappings = new HashEntryTree<>();

        for (int i = 0; i < lines.size(); i++) {
            progress.step(i, "");
            String line = lines.get(i).trim().replaceAll("\n\n+", "");

            if (line.equals("") || line.startsWith("#") || line.startsWith(".option")) continue;

            try {
                MappingPair<?, EntryMapping> mapping = parseLine(line);
                mappings.insert(mapping.getEntry(), mapping.getMapping());
            } catch (Throwable t) {
                t.printStackTrace();
                throw new MappingParseException(path::toString, i, t.toString());
            }
        }

        return mappings;
    }

    private MappingPair<?, EntryMapping> parseLine(String line) {
        String[] tokens = line.split(" ");
        String key = tokens[0];

        switch (key) {
            case ".class_map" -> { return parseClass(tokens); }
            case ".field_map" -> { return parseField(tokens); }
            case ".method_map" -> { return parseMethod(tokens); }
            default -> throw new RuntimeException("Unknown token '" + key + "'!");
        }
    }

    private MappingPair<ClassEntry, EntryMapping> parseClass(String[] tokens) {
        // .class_map a PathPoint
        ClassEntry obfuscatedEntry = new ClassEntry(tokens[1]);
        String mapping = tokens[2];

        // TODO: Do inner classes if those even exist in RGS
        return new MappingPair<>(obfuscatedEntry, new EntryMapping(mapping));
    }

    private MappingPair<FieldEntry, EntryMapping> parseField(String[] tokens) {
        // .field_map ad/e dataFolder
        String obfuscatedClassAndFieldName = tokens[1];

        int lastIndex = obfuscatedClassAndFieldName.lastIndexOf('/');
        String obfuscatedClass = obfuscatedClassAndFieldName.substring(0, lastIndex);
        String obfuscatedField = obfuscatedClassAndFieldName.substring(lastIndex);

        ClassEntry ownerClass = new ClassEntry(obfuscatedClass);
        FieldEntry obfuscatedEntry = new FieldEntry(ownerClass, obfuscatedField, null);
        String mapping = tokens[2];

        return new MappingPair<>(obfuscatedEntry, new EntryMapping(mapping));
    }

    private MappingPair<MethodEntry, EntryMapping> parseMethod(String[] tokens) {
        // .method_map a/a ()Z func_1179_a
        String obfuscatedClassAndMethodName = tokens[1];

        int lastIndex = obfuscatedClassAndMethodName.lastIndexOf('/');
        String obfuscatedClass = obfuscatedClassAndMethodName.substring(0, lastIndex);
        String obfuscatedMethod = obfuscatedClassAndMethodName.substring(lastIndex);

        ClassEntry ownerClass = new ClassEntry(obfuscatedClass);
        MethodDescriptor ownerDescriptor = new MethodDescriptor(tokens[2]);
        MethodEntry ownerMethod = new MethodEntry(ownerClass, obfuscatedMethod, ownerDescriptor);
        String mapping = tokens[3];
        return new MappingPair<>(null, new EntryMapping(mapping));
    }
}
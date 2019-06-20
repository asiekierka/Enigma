package cuchaz.enigma.command;

import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.throwables.MappingParseException;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.serde.*;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.mapping.tree.EntryTreeNode;
import cuchaz.enigma.translation.mapping.tree.HashEntryTree;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import org.checkerframework.checker.units.qual.C;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class MappingCommandsUtil {
    private MappingCommandsUtil() {}

    @SuppressWarnings("unchecked")
    public static EntryTree<EntryMapping> invert(EntryTree<EntryMapping> mappings) {
        EntryTree<EntryMapping> result = new HashEntryTree<>();

        Map<Entry<?>, Entry<?>> rightEntries = new HashMap<>();
        for (EntryTreeNode<EntryMapping> node : mappings) {
            Entry<?> leftEntry = node.getEntry();
            EntryMapping leftMapping = node.getValue();

            if (!(leftEntry instanceof ClassEntry || leftEntry instanceof MethodEntry || leftEntry instanceof FieldEntry)) {
                result.insert(((Entry<Entry<?>>) leftEntry).withParent(rightEntries.get(leftEntry.getParent())), leftMapping);
                continue;
            }

            Entry<?> rightEntry = ((Entry<Entry<?>>) leftEntry)
                    .withParent(rightEntries.get(leftEntry.getParent()))
                    .withName(getInnerName(leftMapping == null ? leftEntry.getName() : leftMapping.getTargetName()));
            rightEntries.put(leftEntry, rightEntry);

            result.insert(rightEntry, leftMapping == null ? null : new EntryMapping(leftEntry.getName())); // TODO: leftMapping.withName once javadoc PR is merged
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public static EntryTree<EntryMapping> compose(EntryTree<EntryMapping> left, EntryTree<EntryMapping> right, boolean keepLeftOnly, boolean keepRightOnly) {
        EntryTree<EntryMapping> result = new HashEntryTree<>();
        Map<Entry<?>, Entry<?>> leftToRight = new HashMap<>();
        Map<Entry<?>, Entry<?>> rightToLeft = new HashMap<>();
        Set<Entry<?>> addedMappings = new HashSet<>();

        for (EntryTreeNode<EntryMapping> node : left) {
            Entry<?> leftEntry = node.getEntry();
            EntryMapping leftMapping = node.getValue();

            Entry<?> rightEntry = ((Entry<Entry<?>>) leftEntry)
                    .withParent(leftToRight.get(leftEntry.getParent()))
                    .withName(getInnerName(leftMapping == null ? leftEntry.getName() : leftMapping.getTargetName()));
            leftToRight.put(leftEntry, rightEntry);
            rightToLeft.put(rightEntry, leftEntry);

            EntryMapping rightMapping = right.get(rightEntry);
            if (rightMapping != null) {
                result.insert(leftEntry, rightMapping);
                addedMappings.add(rightEntry);
            } else if (keepLeftOnly) {
                result.insert(leftEntry, leftMapping);
            }
        }

        if (keepRightOnly) {
            for (EntryTreeNode<EntryMapping> node : right) {
                Entry<?> rightEntry = node.getEntry();
                EntryMapping rightMapping = node.getValue();

                if (addedMappings.contains(rightEntry)) {
                    continue;
                }

                Entry<?> parent = rightEntry.getParent();
                Entry<?> correctEntry = rightEntry;
                if (rightToLeft.containsKey(parent)) {
                    correctEntry = ((Entry<Entry<?>>) rightEntry).withParent(rightToLeft.get(parent));
                }

                result.insert(correctEntry, rightMapping);
                rightToLeft.put(rightEntry, correctEntry);
            }
        }
        return result;
    }

    private static String getInnerName(String name) {
        int lastDollarSignIndex = name.indexOf('$');
        return lastDollarSignIndex < 0 ? name : name.substring(lastDollarSignIndex + 1);
    }

    public static EntryTree<EntryMapping> read(String type, Path path) throws MappingParseException, IOException {
        if (type.equals("enigma")) {
            return EnigmaMappingsReader.DIRECTORY.read(path, ProgressListener.none());
        }

        if (type.equals("tiny")) {
            return TinyMappingsReader.INSTANCE.read(path, ProgressListener.none());
        }

        MappingFormat format = null;
        try {
            format = MappingFormat.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException ignored) {}

        if (format != null) {
            return format.getReader().read(path, ProgressListener.none());
        }

        throw new IllegalArgumentException("no reader for " + type);
    }

    public static void write(EntryTree<EntryMapping> mappings, String type, Path path) {
        if (type.equals("enigma")) {
            EnigmaMappingsWriter.DIRECTORY.write(mappings, path, ProgressListener.none());
            return;
        }

        if (type.startsWith("tiny")) {
            String[] split = type.split(":");

            if (split.length != 3) {
                throw new IllegalArgumentException("specify column names as 'tiny:from_column:to_column'");
            }

            new TinyMappingsWriter(split[1], split[2]).write(mappings, path, ProgressListener.none());
            return;
        }

        MappingFormat format = null;
        try {
            format = MappingFormat.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException ignored) {}

        if (format != null) {
            format.getWriter().write(mappings, path, ProgressListener.none());
            return;
        }

        throw new IllegalArgumentException("no writer for " + type);
    }
}

/*******************************************************************************
 * Copyright (c) 2015 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public
 * License v3.0 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Contributors:
 * Jeff Martin - initial API and implementation
 ******************************************************************************/

package cuchaz.enigma.translation.mapping;

import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.mapping.tree.HashTreeNode;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class MappingsChecker {
	private final JarIndex index;
	private final EntryTree<EntryMapping> mappings;

	public MappingsChecker(JarIndex index, EntryTree<EntryMapping> mappings) {
		this.index = index;
		this.mappings = mappings;
	}

	public Dropped dropBrokenMappings() {
		Dropped dropped = new Dropped();

		Collection<Entry<?>> obfEntries = mappings.getAllEntries();
		for (Entry<?> entry : obfEntries) {
			if (entry instanceof ClassEntry || entry instanceof MethodEntry || entry instanceof FieldEntry) {
				tryDropEntry(dropped, entry);
			}
		}

		dropped.apply(mappings);

		return dropped;
	}

	private void tryDropEntry(Dropped dropped, Entry<?> entry) {
		if (shouldDropEntry(entry)) {
			EntryMapping mapping = mappings.get(entry);
			if (mapping != null) {
				dropped.drop(entry, mapping);
			}
		}
	}

	private boolean shouldDropEntry(Entry<?> entry) {
		if (!index.getEntryIndex().hasEntry(entry)) {
			return true;
		}
		Entry<?> resolvedEntry = index.getEntryResolver().resolveEntry(entry);
		return !entry.equals(resolvedEntry);
	}

	public static class Dropped {
		private final Map<Entry<?>, String> droppedMappings = new HashMap<>();

		public void drop(Entry<?> entry, EntryMapping mapping) {
			droppedMappings.put(entry, mapping.getTargetName());
		}

		void apply(EntryTree<EntryMapping> mappings) {
			for (Entry<?> entry : droppedMappings.keySet()) {
				HashTreeNode<EntryMapping> node = mappings.findNode(entry);
				if (node == null) {
					continue;
				}

				for (Entry<?> childEntry : node.getChildrenRecursively()) {
					mappings.remove(childEntry);
				}
			}
		}

		public Map<Entry<?>, String> getDroppedMappings() {
			return droppedMappings;
		}
	}
}

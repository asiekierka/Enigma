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

package cuchaz.enigma.analysis;

import com.google.common.collect.Lists;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;

public class ClassImplementationsTreeNode extends DefaultMutableTreeNode {

	private final ClassEntry entry;

	public ClassImplementationsTreeNode(ClassEntry entry) {
		this.entry = entry;
	}

	public static ClassImplementationsTreeNode findNode(ClassImplementationsTreeNode node, MethodEntry entry) {
		// is this the node?
		if (node.entry.equals(entry.getParent())) {
			return node;
		}

		// recurse
		for (int i = 0; i < node.getChildCount(); i++) {
			ClassImplementationsTreeNode foundNode = findNode((ClassImplementationsTreeNode) node.getChildAt(i), entry);
			if (foundNode != null) {
				return foundNode;
			}
		}
		return null;
	}

	public ClassEntry getClassEntry() {
		return this.entry;
	}

	@Override
	public String toString() {
		return entry.toString();
	}

	public void load(JarIndex index) {
		// get all method implementations
		List<ClassImplementationsTreeNode> nodes = Lists.newArrayList();
		for (String implementingClassName : index.getImplementingClasses(this.entry.getFullName())) {
			nodes.add(new ClassImplementationsTreeNode(new ClassEntry(implementingClassName)));
		}

		// add them to this node
		nodes.forEach(this::add);
	}
}

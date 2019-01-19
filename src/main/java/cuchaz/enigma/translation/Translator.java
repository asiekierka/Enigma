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

package cuchaz.enigma.translation;

import java.util.Collection;
import java.util.stream.Collectors;

public interface Translator {
	<T extends Translatable> T translate(T translatable);

	default <T extends Translatable> Collection<T> translate(Collection<T> translatable) {
		return translatable.stream()
				.map(this::translate)
				.collect(Collectors.toList());
	}
}

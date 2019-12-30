/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.util.Objects;

import org.objectweb.asm.tree.FieldNode;

public class FieldComparison {
	public final FieldNode node;
	public final AccessChange access;
	public final FinalityChange finality;

	public FieldComparison(FieldNode original, FieldNode patched) {
		assert Objects.equals(original.name, patched.name);
		assert Objects.equals(original.desc, patched.desc);
		node = patched;

		access = AccessChange.forAccess(original.access, patched.access);
		finality = FinalityChange.forAccess(original.access, patched.access);
	}

	public boolean hasChanged() {
		return access != AccessChange.NONE || finality != FinalityChange.NONE;
	}

	public ChangeSet toChangeSet() {
		return new ChangeSet(access, finality);
	}
}
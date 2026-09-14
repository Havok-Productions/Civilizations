package dev.civilizations.core;

/** A block actually observed in a bed-associated structure, including its orientation. */
public record RepairBlock(Pos position, String material, String blockData) {}

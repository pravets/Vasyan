package ru.pravets.vasyan.navigation;

/**
 * Kind of edge a pathfinding step makes between two cells. Used to price and
 * execute steps differently: walking is free-ish, digging spends break time,
 * placing consumes an inventory block.
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public enum MoveType {
    WALK, DIG, PLACE, PILLAR_UP
}

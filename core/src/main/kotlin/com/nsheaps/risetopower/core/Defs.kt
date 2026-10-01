package com.nsheaps.risetopower.core

import kotlin.math.roundToInt

enum class ResourceType(val displayName: String) {
    FOOD("Food"), WOOD("Wood"), GOLD("Gold"), STONE("Stone");
}

data class Cost(val food: Int = 0, val wood: Int = 0, val gold: Int = 0, val stone: Int = 0) {
    operator fun get(r: ResourceType): Int = when (r) {
        ResourceType.FOOD -> food
        ResourceType.WOOD -> wood
        ResourceType.GOLD -> gold
        ResourceType.STONE -> stone
    }

    fun scaled(f: Float) = Cost(
        (food * f).roundToInt(), (wood * f).roundToInt(),
        (gold * f).roundToInt(), (stone * f).roundToInt()
    )

    val isFree get() = food == 0 && wood == 0 && gold == 0 && stone == 0

    /** Short human readable string, e.g. "50F 25W". */
    fun label(): String {
        val parts = ArrayList<String>(4)
        if (food > 0) parts += "${food}F"
        if (wood > 0) parts += "${wood}W"
        if (gold > 0) parts += "${gold}G"
        if (stone > 0) parts += "${stone}S"
        return if (parts.isEmpty()) "Free" else parts.joinToString(" ")
    }

    companion object {
        val NONE = Cost()
    }
}

enum class Age(val displayName: String, val advanceCost: Cost, val advanceTime: Float) {
    ANCIENT("Ancient Age", Cost.NONE, 0f),
    CLASSICAL("Classical Age", Cost(food = 400, wood = 200), 40f),
    MEDIEVAL("Medieval Age", Cost(food = 800, wood = 300, gold = 200), 55f),
    GUNPOWDER("Gunpowder Age", Cost(food = 1200, wood = 500, gold = 600), 70f),
    INDUSTRIAL("Industrial Age", Cost(food = 1800, wood = 800, gold = 1000, stone = 200), 85f);

    val next: Age? get() = entries.getOrNull(ordinal + 1)
}

/** Classes used for attack bonuses and armor. */
enum class ArmorClass { VILLAGER, INFANTRY, RANGED, CAVALRY, SIEGE, SUPPORT, BUILDING }

enum class UnitType(
    val armorClass: ArmorClass,
    val minAge: Age,
    val cost: Cost,
    val trainTime: Float,
    val hp: Int,
    val attack: Int,
    /** Attack range in tiles measured edge to edge. Melee units use a small value. */
    val range: Float,
    val cooldown: Float,
    val meleeArmor: Int,
    val pierceArmor: Int,
    val speed: Float,
    val sight: Float,
    val pop: Int,
    val ranged: Boolean,
    val bonus: Map<ArmorClass, Float>,
    val splash: Float,
    val ageNames: List<String>,
    val description: String,
) {
    VILLAGER(
        ArmorClass.VILLAGER, Age.ANCIENT, Cost(food = 50), 12f, 40, 3, 0.35f, 1.5f, 0, 1, 1.6f, 6f, 1, false,
        emptyMap(), 0f, listOf("Citizen"), "Gathers resources and constructs buildings."
    ),
    SCOUT(
        ArmorClass.CAVALRY, Age.ANCIENT, Cost(food = 70), 14f, 70, 3, 0.4f, 1.5f, 0, 2, 3.0f, 11f, 1, false,
        mapOf(ArmorClass.SUPPORT to 3f), 0f, listOf("Scout", "Outrider", "Light Rider", "Hussar", "Cavalry Scout"),
        "Fast explorer with a wide line of sight."
    ),
    SPEARMAN(
        ArmorClass.INFANTRY, Age.ANCIENT, Cost(food = 50, wood = 25), 14f, 70, 5, 0.4f, 1.4f, 1, 1, 1.5f, 6f, 1, false,
        mapOf(ArmorClass.CAVALRY to 3f), 0f, listOf("Spearman", "Hoplite", "Pikeman", "Halberdier", "Fusilier"),
        "Cheap infantry. Strong against cavalry."
    ),
    WARRIOR(
        ArmorClass.INFANTRY, Age.ANCIENT, Cost(food = 60, gold = 20), 16f, 85, 8, 0.4f, 1.3f, 2, 1, 1.4f, 6f, 1, false,
        mapOf(ArmorClass.BUILDING to 2f, ArmorClass.SIEGE to 1.5f), 0f,
        listOf("Warrior", "Legionary", "Man-at-Arms", "Grenadier", "Guardsman"),
        "Heavy infantry. Good against buildings and siege."
    ),
    ARCHER(
        ArmorClass.RANGED, Age.ANCIENT, Cost(food = 30, wood = 45), 15f, 45, 5, 5f, 1.6f, 0, 0, 1.5f, 7f, 1, true,
        mapOf(ArmorClass.INFANTRY to 1.5f), 0f, listOf("Bowman", "Archer", "Crossbowman", "Arquebusier", "Rifleman"),
        "Ranged foot soldier. Strong against infantry."
    ),
    HORSEMAN(
        ArmorClass.CAVALRY, Age.CLASSICAL, Cost(food = 80, gold = 50), 18f, 120, 9, 0.45f, 1.6f, 2, 2, 2.6f, 7f, 1, false,
        mapOf(ArmorClass.RANGED to 2f, ArmorClass.SIEGE to 2f), 0f,
        listOf("Horseman", "Knight", "Cuirassier", "Lancer"),
        "Shock cavalry. Strong against ranged units and siege."
    ),
    HORSE_ARCHER(
        ArmorClass.CAVALRY, Age.MEDIEVAL, Cost(food = 60, wood = 40, gold = 70), 20f, 95, 6, 4.5f, 1.8f, 1, 2, 2.4f, 8f, 1, true,
        mapOf(ArmorClass.INFANTRY to 1.4f), 0f, listOf("Horse Archer", "Carabinier", "Dragoon"),
        "Mobile mounted ranged unit."
    ),
    CATAPULT(
        ArmorClass.SIEGE, Age.CLASSICAL, Cost(wood = 150, gold = 100), 25f, 120, 35, 7f, 4f, 2, 8, 1.0f, 8f, 2, true,
        mapOf(ArmorClass.BUILDING to 3f), 0.9f, listOf("Catapult", "Trebuchet", "Bombard", "Artillery"),
        "Siege engine. Devastating against buildings, deals splash damage."
    ),
    HEALER(
        ArmorClass.SUPPORT, Age.CLASSICAL, Cost(gold = 100), 20f, 40, 0, 4f, 1f, 0, 0, 1.3f, 7f, 1, false,
        emptyMap(), 0f, listOf("Priest", "Monk", "Physician", "Medic"),
        "Heals nearby wounded units."
    );

    val isMilitary get() = this != VILLAGER
}

enum class BuildingType(
    val displayName: String,
    val size: Int,
    val hp: Int,
    val cost: Cost,
    val buildTime: Float,
    val minAge: Age,
    val pop: Int,
    val dropOff: Set<ResourceType>,
    val sight: Float,
    val attack: Int,
    val range: Float,
    val territory: Float,
    val blocksMovement: Boolean,
    val description: String,
) {
    TOWN_CENTER(
        "Town Center", 3, 2400, Cost(wood = 300, stone = 200), 90f, Age.ANCIENT, 10, ResourceType.entries.toSet(),
        10f, 6, 7f, 10f, true, "Trains citizens, advances ages and claims territory."
    ),
    HOUSE("House", 2, 550, Cost(wood = 50), 20f, Age.ANCIENT, 10, emptySet(), 4f, 0, 0f, 0f, true, "Supports 10 population."),
    FARM("Farm", 2, 250, Cost(wood = 60), 12f, Age.ANCIENT, 0, emptySet(), 2f, 0, 0f, 0f, false, "Endless food for one citizen."),
    MILL("Mill", 2, 600, Cost(wood = 100), 25f, Age.ANCIENT, 0, setOf(ResourceType.FOOD), 5f, 0, 0f, 0f, true, "Food drop-off and farming technology."),
    LUMBER_CAMP("Lumber Camp", 2, 600, Cost(wood = 100), 25f, Age.ANCIENT, 0, setOf(ResourceType.WOOD), 5f, 0, 0f, 0f, true, "Wood drop-off and forestry technology."),
    MINING_CAMP("Mining Camp", 2, 600, Cost(wood = 100), 25f, Age.ANCIENT, 0, setOf(ResourceType.GOLD, ResourceType.STONE), 5f, 0, 0f, 0f, true, "Gold and stone drop-off and mining technology."),
    BARRACKS("Barracks", 3, 1200, Cost(wood = 150), 35f, Age.ANCIENT, 0, emptySet(), 5f, 0, 0f, 0f, true, "Trains infantry."),
    ARCHERY_RANGE("Archery Range", 3, 1200, Cost(wood = 150), 35f, Age.ANCIENT, 0, emptySet(), 5f, 0, 0f, 0f, true, "Trains ranged foot soldiers."),
    STABLE("Stable", 3, 1200, Cost(wood = 175), 35f, Age.ANCIENT, 0, emptySet(), 5f, 0, 0f, 0f, true, "Trains scouts and cavalry."),
    BLACKSMITH("Blacksmith", 2, 900, Cost(wood = 150), 30f, Age.ANCIENT, 0, emptySet(), 5f, 0, 0f, 0f, true, "Weapon and armor upgrades."),
    LIBRARY("Library", 3, 1000, Cost(wood = 150), 35f, Age.ANCIENT, 0, emptySet(), 5f, 0, 0f, 0f, true, "Civic and scientific research."),
    SIEGE_WORKSHOP("Siege Workshop", 3, 1200, Cost(wood = 200, gold = 50), 40f, Age.CLASSICAL, 0, emptySet(), 5f, 0, 0f, 0f, true, "Builds siege engines."),
    TEMPLE("Temple", 3, 1200, Cost(wood = 100, stone = 100), 40f, Age.CLASSICAL, 0, emptySet(), 6f, 0, 0f, 0f, true, "Trains healers, religious research."),
    MARKET("Market", 3, 1200, Cost(wood = 175), 40f, Age.CLASSICAL, 0, emptySet(), 6f, 0, 0f, 0f, true, "Trade resources for gold. Generates trade income."),
    TOWER("Tower", 2, 1100, Cost(wood = 50, stone = 125), 35f, Age.ANCIENT, 0, emptySet(), 9f, 7, 7f, 4f, true, "Defensive tower that extends your borders."),
    WALL("Wall", 1, 900, Cost(stone = 6), 6f, Age.ANCIENT, 0, emptySet(), 3f, 0, 0f, 0f, true, "Stone wall segment. Drag to place a line."),
    FORTRESS("Fortress", 4, 4200, Cost(wood = 200, stone = 600), 90f, Age.MEDIEVAL, 0, emptySet(), 10f, 12, 8f, 8f, true, "Powerful stronghold. Trains elite troops."),
    WONDER("Wonder", 4, 6000, Cost(food = 1000, wood = 1000, gold = 1000, stone = 1000), 240f, Age.MEDIEVAL, 0, emptySet(), 8f, 0, 0f, 0f, true, "Hold it for 5 minutes to win.");

    fun trains(): List<UnitType> = when (this) {
        TOWN_CENTER -> listOf(UnitType.VILLAGER)
        BARRACKS -> listOf(UnitType.SPEARMAN, UnitType.WARRIOR)
        ARCHERY_RANGE -> listOf(UnitType.ARCHER)
        STABLE -> listOf(UnitType.SCOUT, UnitType.HORSEMAN, UnitType.HORSE_ARCHER)
        SIEGE_WORKSHOP -> listOf(UnitType.CATAPULT)
        TEMPLE -> listOf(UnitType.HEALER)
        FORTRESS -> listOf(UnitType.WARRIOR, UnitType.HORSEMAN, UnitType.CATAPULT)
        else -> emptyList()
    }

    fun researches(): List<Tech> = Tech.entries.filter { it.building == this }

    val isDropSite get() = dropOff.isNotEmpty()
}

/** Things a player may construct, grouped for the build menu. */
object BuildMenu {
    val economy = listOf(
        BuildingType.HOUSE, BuildingType.FARM, BuildingType.MILL, BuildingType.LUMBER_CAMP,
        BuildingType.MINING_CAMP, BuildingType.MARKET, BuildingType.LIBRARY, BuildingType.TOWN_CENTER, BuildingType.WONDER,
    )
    val military = listOf(
        BuildingType.BARRACKS, BuildingType.ARCHERY_RANGE, BuildingType.STABLE, BuildingType.SIEGE_WORKSHOP,
        BuildingType.BLACKSMITH, BuildingType.TEMPLE, BuildingType.TOWER, BuildingType.WALL, BuildingType.FORTRESS,
    )
}

enum class Tech(
    val displayName: String,
    val building: BuildingType,
    val minAge: Age,
    val cost: Cost,
    val time: Float,
    val description: String,
    val requires: Tech? = null,
) {
    // Mill
    IRRIGATION("Irrigation", BuildingType.MILL, Age.ANCIENT, Cost(food = 75, wood = 75), 20f, "+15% farm and forage rate"),
    CROP_ROTATION("Crop Rotation", BuildingType.MILL, Age.MEDIEVAL, Cost(food = 250, wood = 150), 35f, "+20% food gather rate", IRRIGATION),
    FERTILIZER("Fertilizer", BuildingType.MILL, Age.INDUSTRIAL, Cost(food = 400, wood = 250), 45f, "+25% food gather rate", CROP_ROTATION),

    // Lumber camp
    AXES("Bronze Axes", BuildingType.LUMBER_CAMP, Age.ANCIENT, Cost(food = 75, wood = 50), 20f, "+15% wood gather rate"),
    SAWMILL("Sawmill", BuildingType.LUMBER_CAMP, Age.MEDIEVAL, Cost(food = 200, wood = 150), 35f, "+20% wood gather rate", AXES),
    STEAM_SAWS("Steam Saws", BuildingType.LUMBER_CAMP, Age.INDUSTRIAL, Cost(food = 350, wood = 250), 45f, "+25% wood gather rate", SAWMILL),

    // Mining camp
    PICKAXES("Pickaxes", BuildingType.MINING_CAMP, Age.ANCIENT, Cost(food = 75, wood = 75), 20f, "+15% gold and stone rate"),
    SHAFT_MINING("Shaft Mining", BuildingType.MINING_CAMP, Age.MEDIEVAL, Cost(food = 200, wood = 200), 35f, "+20% gold and stone rate", PICKAXES),
    DYNAMITE("Dynamite", BuildingType.MINING_CAMP, Age.GUNPOWDER, Cost(food = 300, wood = 300, gold = 100), 45f, "+25% gold and stone rate", SHAFT_MINING),

    // Town center
    WHEELBARROW("Wheelbarrow", BuildingType.TOWN_CENTER, Age.CLASSICAL, Cost(food = 175, wood = 50), 30f, "Citizens carry +4 and move 10% faster"),
    HANDCART("Handcart", BuildingType.TOWN_CENTER, Age.GUNPOWDER, Cost(food = 300, wood = 200), 45f, "Citizens carry +4 and move 10% faster", WHEELBARROW),

    // Blacksmith
    FORGING("Forging", BuildingType.BLACKSMITH, Age.CLASSICAL, Cost(food = 150, gold = 50), 30f, "+1 melee attack"),
    IRON_CASTING("Iron Casting", BuildingType.BLACKSMITH, Age.MEDIEVAL, Cost(food = 220, gold = 120), 40f, "+2 melee attack", FORGING),
    FLETCHING("Fletching", BuildingType.BLACKSMITH, Age.CLASSICAL, Cost(food = 100, wood = 50, gold = 50), 30f, "+1 ranged attack and +1 range"),
    BODKIN_ARROWS("Bodkin Arrows", BuildingType.BLACKSMITH, Age.MEDIEVAL, Cost(food = 200, wood = 100, gold = 100), 40f, "+2 ranged attack", FLETCHING),
    SCALE_MAIL("Scale Mail", BuildingType.BLACKSMITH, Age.CLASSICAL, Cost(food = 120, gold = 60), 30f, "+1 melee / +1 pierce armor"),
    PLATE_MAIL("Plate Mail", BuildingType.BLACKSMITH, Age.GUNPOWDER, Cost(food = 300, gold = 200), 45f, "+2 melee / +2 pierce armor", SCALE_MAIL),

    // Library
    CARTOGRAPHY("Cartography", BuildingType.LIBRARY, Age.ANCIENT, Cost(food = 60, wood = 60), 20f, "+2 line of sight for all units"),
    CIVICS("Civics", BuildingType.LIBRARY, Age.CLASSICAL, Cost(food = 150, wood = 150), 30f, "+3 border radius, +25 population limit"),
    ENGINEERING("Engineering", BuildingType.LIBRARY, Age.MEDIEVAL, Cost(wood = 200, gold = 150), 40f, "+25% building HP, +25% build speed"),
    TAXATION("Taxation", BuildingType.LIBRARY, Age.MEDIEVAL, Cost(food = 200, gold = 100), 40f, "Each Town Center generates gold"),
    BALLISTICS("Ballistics", BuildingType.LIBRARY, Age.GUNPOWDER, Cost(wood = 250, gold = 250), 45f, "+25% siege attack, towers +3 attack"),
    CONSCRIPTION("Conscription", BuildingType.LIBRARY, Age.GUNPOWDER, Cost(food = 300, gold = 200), 45f, "Military trains 33% faster"),
    NATIONALISM("Nationalism", BuildingType.LIBRARY, Age.INDUSTRIAL, Cost(food = 500, gold = 500), 60f, "+20% HP for all military, +50 population limit", CIVICS),

    // Temple
    MEDICINE("Medicine", BuildingType.TEMPLE, Age.CLASSICAL, Cost(gold = 150), 30f, "Healers heal twice as fast"),
    FAITH("Faith", BuildingType.TEMPLE, Age.MEDIEVAL, Cost(gold = 200), 40f, "Enemy units suffer double attrition in your lands"),

    // Market
    COINAGE("Coinage", BuildingType.MARKET, Age.CLASSICAL, Cost(food = 150, gold = 50), 30f, "Better market prices, +50% trade income"),
    BANKING("Banking", BuildingType.MARKET, Age.GUNPOWDER, Cost(food = 300, gold = 200), 45f, "+100% trade income", COINAGE),

    // Tower / fortress improvements researched at the town center
    MASONRY("Masonry", BuildingType.TOWN_CENTER, Age.CLASSICAL, Cost(wood = 100, stone = 100), 30f, "+20% building HP, towers +2 attack"),
}

enum class Civ(val displayName: String, val bonus: String) {
    ROMANS("Romans", "Infantry +15% HP. Barracks units train 15% faster."),
    GREEKS("Greeks", "Technologies cost 25% less."),
    EGYPTIANS("Egyptians", "Farms and foraging +20% food."),
    MONGOLS("Mongols", "Cavalry +15% speed and +10% attack."),
    CHINESE("Chinese", "Start with 2 extra citizens. Citizens train 25% faster."),
    BRITONS("Britons", "Ranged units +1 range and +10% attack."),
}

enum class Difficulty(val displayName: String, val gatherMult: Float, val thinkInterval: Float, val attackThreshold: Int, val firstAttackTime: Float) {
    EASY("Easy", 0.75f, 2.0f, 14, 900f),
    NORMAL("Normal", 1.0f, 1.2f, 10, 600f),
    HARD("Hard", 1.2f, 0.8f, 8, 420f),
    BRUTAL("Brutal", 1.45f, 0.5f, 7, 330f),
}

enum class MapType(val displayName: String) {
    CONTINENTAL("Continental"),
    HIGHLANDS("Highlands"),
    LAKES("Great Lakes"),
    FOREST("Black Forest"),
}

enum class MapSize(val displayName: String, val tiles: Int) {
    SMALL("Small", 64), MEDIUM("Medium", 96), LARGE("Large", 128);
}

enum class Terrain(val walkable: Boolean, val buildable: Boolean) {
    GRASS(true, true),
    DIRT(true, true),
    SAND(true, true),
    SHALLOWS(true, false),
    WATER(false, false),
    MOUNTAIN(false, false),
}

enum class NodeKind(val resource: ResourceType, val size: Int, val amount: Int, val displayName: String) {
    TREE(ResourceType.WOOD, 1, 120, "Tree"),
    BERRIES(ResourceType.FOOD, 1, 150, "Berry Bush"),
    GAME(ResourceType.FOOD, 1, 300, "Wild Game"),
    GOLD(ResourceType.GOLD, 2, 1500, "Gold Mine"),
    STONE(ResourceType.STONE, 2, 1200, "Stone Quarry"),
}

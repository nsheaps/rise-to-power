package com.nsheaps.risetopower.core

class Player(
    val id: Int,
    val name: String,
    val color: Int,
    val civ: Civ,
    val isHuman: Boolean,
    val team: Int,
    val difficulty: Difficulty,
) {
    val stock = FloatArray(4)
    var age = Age.ANCIENT
    val techs = HashSet<Tech>()
    var defeated = false
    var popUsed = 0
    var popCap = 0

    /** Market prices in gold per 100 units of food, wood, stone (index by ResourceType, gold unused). */
    val marketPrice = floatArrayOf(100f, 100f, 100f, 100f)

    // Statistics
    val gathered = FloatArray(4)
    var unitsKilled = 0
    var unitsLost = 0
    var buildingsDestroyed = 0
    var buildingsLost = 0
    var techCount = 0

    // Fog of war state; only maintained for human players.
    var explored: BooleanArray = BooleanArray(0)
    var visible: ByteArray = ByteArray(0)

    fun has(t: Tech) = t in techs

    fun canAfford(c: Cost) = stock[0] >= c.food && stock[1] >= c.wood && stock[2] >= c.gold && stock[3] >= c.stone

    fun pay(c: Cost): Boolean {
        if (!canAfford(c)) return false
        stock[0] -= c.food; stock[1] -= c.wood; stock[2] -= c.gold; stock[3] -= c.stone
        return true
    }

    fun refund(c: Cost, fraction: Float = 1f) {
        stock[0] += c.food * fraction; stock[1] += c.wood * fraction
        stock[2] += c.gold * fraction; stock[3] += c.stone * fraction
    }

    operator fun get(r: ResourceType) = stock[r.ordinal]

    fun add(r: ResourceType, amount: Float) {
        stock[r.ordinal] += amount
        gathered[r.ordinal] += amount
    }

    // ---- Modifiers derived from age, technology and civilization ----

    fun gatherMult(r: ResourceType): Float {
        var m = 1f
        when (r) {
            ResourceType.FOOD -> {
                if (has(Tech.IRRIGATION)) m += 0.15f
                if (has(Tech.CROP_ROTATION)) m += 0.2f
                if (has(Tech.FERTILIZER)) m += 0.25f
                if (civ == Civ.EGYPTIANS) m += 0.2f
            }
            ResourceType.WOOD -> {
                if (has(Tech.AXES)) m += 0.15f
                if (has(Tech.SAWMILL)) m += 0.2f
                if (has(Tech.STEAM_SAWS)) m += 0.25f
            }
            ResourceType.GOLD, ResourceType.STONE -> {
                if (has(Tech.PICKAXES)) m += 0.15f
                if (has(Tech.SHAFT_MINING)) m += 0.2f
                if (has(Tech.DYNAMITE)) m += 0.25f
            }
        }
        if (!isHuman) m *= difficulty.gatherMult
        return m
    }

    val carryCapacity: Float
        get() = 10f + (if (has(Tech.WHEELBARROW)) 4f else 0f) + (if (has(Tech.HANDCART)) 4f else 0f)

    fun unitLevel(t: UnitType) = (age.ordinal - t.minAge.ordinal).coerceAtLeast(0)

    fun unitName(t: UnitType) = t.ageNames[unitLevel(t).coerceAtMost(t.ageNames.size - 1)]

    fun unitMaxHp(t: UnitType): Float {
        var hp = t.hp * (1f + 0.2f * unitLevel(t))
        if (civ == Civ.ROMANS && t.armorClass == ArmorClass.INFANTRY) hp *= 1.15f
        if (has(Tech.NATIONALISM) && t.isMilitary) hp *= 1.2f
        return hp
    }

    fun unitAttack(t: UnitType): Float {
        var a = t.attack * (1f + 0.2f * unitLevel(t))
        if (t.ranged && t != UnitType.CATAPULT) {
            if (has(Tech.FLETCHING)) a += 1
            if (has(Tech.BODKIN_ARROWS)) a += 2
            if (civ == Civ.BRITONS) a *= 1.1f
        } else if (t != UnitType.CATAPULT && t != UnitType.HEALER) {
            if (has(Tech.FORGING)) a += 1
            if (has(Tech.IRON_CASTING)) a += 2
        }
        if (t == UnitType.CATAPULT && has(Tech.BALLISTICS)) a *= 1.25f
        if (civ == Civ.MONGOLS && t.armorClass == ArmorClass.CAVALRY) a *= 1.1f
        return a
    }

    fun unitRange(t: UnitType): Float {
        var r = t.range
        if (t.ranged && t != UnitType.CATAPULT) {
            if (has(Tech.FLETCHING)) r += 1f
            if (civ == Civ.BRITONS) r += 1f
        }
        return r
    }

    fun unitMeleeArmor(t: UnitType): Float {
        var a = t.meleeArmor + 0.5f * unitLevel(t)
        if (t.isMilitary && t != UnitType.CATAPULT) {
            if (has(Tech.SCALE_MAIL)) a += 1
            if (has(Tech.PLATE_MAIL)) a += 2
        }
        return a
    }

    fun unitPierceArmor(t: UnitType): Float {
        var a = t.pierceArmor + 0.5f * unitLevel(t)
        if (t.isMilitary && t != UnitType.CATAPULT) {
            if (has(Tech.SCALE_MAIL)) a += 1
            if (has(Tech.PLATE_MAIL)) a += 2
        }
        return a
    }

    fun unitSpeed(t: UnitType): Float {
        var s = t.speed
        if (t == UnitType.VILLAGER) {
            if (has(Tech.WHEELBARROW)) s *= 1.1f
            if (has(Tech.HANDCART)) s *= 1.1f
        }
        if (civ == Civ.MONGOLS && t.armorClass == ArmorClass.CAVALRY) s *= 1.15f
        return s
    }

    fun unitSight(t: UnitType) = t.sight + (if (has(Tech.CARTOGRAPHY)) 2f else 0f)

    fun trainTime(t: UnitType): Float {
        var time = t.trainTime
        if (t == UnitType.VILLAGER && civ == Civ.CHINESE) time *= 0.75f
        if (civ == Civ.ROMANS && (t == UnitType.SPEARMAN || t == UnitType.WARRIOR)) time *= 0.85f
        if (t.isMilitary && has(Tech.CONSCRIPTION)) time *= 0.67f
        return time
    }

    fun techCost(t: Tech): Cost = if (civ == Civ.GREEKS) t.cost.scaled(0.75f) else t.cost

    fun buildingMaxHp(t: BuildingType): Float {
        var hp = t.hp.toFloat()
        if (has(Tech.MASONRY)) hp *= 1.2f
        if (has(Tech.ENGINEERING)) hp *= 1.25f
        return hp
    }

    fun buildingAttack(t: BuildingType): Float {
        var a = t.attack + age.ordinal.toFloat()
        if (has(Tech.MASONRY)) a += 2
        if (has(Tech.BALLISTICS)) a += 3
        return a
    }

    val buildSpeed get() = if (has(Tech.ENGINEERING)) 1.25f else 1f

    val territoryBonus get() = if (has(Tech.CIVICS)) 3f else 0f

    val popLimit get() = 200 + (if (has(Tech.CIVICS)) 25 else 0) + (if (has(Tech.NATIONALISM)) 50 else 0)

    val healRate get() = if (has(Tech.MEDICINE)) 4f else 2f

    val marketSpread get() = if (has(Tech.COINAGE)) 0.15f else 0.3f

    val tradeIncomeMult get() = 1f + (if (has(Tech.COINAGE)) 0.5f else 0f) + (if (has(Tech.BANKING)) 1f else 0f)
}

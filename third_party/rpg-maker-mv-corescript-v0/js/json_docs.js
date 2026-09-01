//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an actor.
 *
 * @class ~ Actor
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The profile.
 *
 * @property profile
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The array of {{#crossLink "~ Trait"}}Trait{{/crossLink}} data.
 *
 * @property traits
 * @type Array
 */

/**
 * The nickname.
 *
 * @property nickname
 * @type String
 */

/**
 * The class ID.
 *
 * @property classId
 * @type Number
 */

/**
 * The initial level.
 *
 * @property initialLevel
 * @type Number
 */

/**
 * The max level.
 *
 * @property maxLevel
 * @type Number
 */

/**
 * The file name of the character image.
 *
 * @property characterName
 * @type String
 */

/**
 * The index of the character image.
 *
 * @property characterIndex
 * @type Number
 */

/**
 * The file name of the face image.
 *
 * @property faceName
 * @type String
 */

/**
 * The index of the face image.
 *
 * @property faceIndex
 * @type Number
 */

/**
 * The file name of the side-view battler image.
 *
 * @property battlerName
 * @type String
 */

/**
 * The initial equipment.
 *
 * @property equips
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a class.
 *
 * @class ~ Class
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The array of {{#crossLink "~ Trait"}}Trait{{/crossLink}} data.
 *
 * @property traits
 * @type Array
 */

/**
 * The array of values that decides the EXP curve.
 *
 * @property expParams
 * @type Array
 */

/**
 * The two-dimensional array containing the parameter values according to level.
 *
 * @property params
 * @type Array
 */

/**
 * The skills to learn.
 * The array of {{#crossLink "~ Class::Learning"}}Learning{{/crossLink}} data.
 *
 * @property learnings
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a skill to learn.
 *
 * @class ~ Class::Learning
 */

/**
 * The level.
 *
 * @property level
 * @type Number
 */

/**
 * The ID of the skill to learn.
 *
 * @property skillId
 * @type Number
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a skill.
 *
 * @class ~ Skill
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The description.
 *
 * @property description
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The index of the icon image.
 *
 * @property iconIndex
 * @type Number
 */

/**
 * The scope.
 *
 * @property scope
 * @type Number
 */

/**
 * The occasion.
 *
 * @property occasion
 * @type Number
 */

/**
 * The speed.
 *
 * @property speed
 * @type Number
 */

/**
 * The success rate.
 *
 * @property successRate
 * @type Number
 */

/**
 * The number of repeats.
 *
 * @property repeats
 * @type Number
 */

/**
 * The TP gain.
 *
 * @property tpGain
 * @type Number
 */

/**
 * The hit type.
 *
 * @property hitType
 * @type Number
 */

/**
 * The animation ID.
 *
 * @property animationId
 * @type Number
 */

/**
 * The damage.
 *
 * @property damage
 * @type ~ Damage
 */

/**
 * The array of {{#crossLink "~ Effect"}}Effect{{/crossLink}} data.
 *
 * @property effects
 * @type Array
 */

/**
 * The skill type ID.
 *
 * @property stypeId
 * @type Number
 */

/**
 * The MP cost.
 *
 * @property mpCost
 * @type Number
 */

/**
 * The TP cost.
 *
 * @property tpCost
 * @type Number
 */

/**
 * The message line 1.
 *
 * @property message1
 * @type String
 */

/**
 * The message line 2.
 *
 * @property message2
 * @type String
 */

/**
 * The required weapon type 1.
 *
 * @property requiredWtypeId1
 * @type Number
 */

/**
 * The required weapon type 2.
 *
 * @property requiredWtypeId2
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an Item.
 *
 * @class ~ Item
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The description.
 *
 * @property description
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The index of the icon image.
 *
 * @property iconIndex
 * @type Number
 */

/**
 * The scope.
 *
 * @property scope
 * @type Number
 */

/**
 * The occasion.
 *
 * @property occasion
 * @type Number
 */

/**
 * The speed.
 *
 * @property speed
 * @type Number
 */

/**
 * The success rate.
 *
 * @property successRate
 * @type Number
 */

/**
 * The number of repeats.
 *
 * @property repeats
 * @type Number
 */

/**
 * The TP gain.
 *
 * @property tpGain
 * @type Number
 */

/**
 * The hit type.
 *
 * @property hitType
 * @type Number
 */

/**
 * The animation ID.
 *
 * @property animationId
 * @type Number
 */

/**
 * The damage.
 *
 * @property damage
 * @type ~ Damage
 */

/**
 * The array of {{#crossLink "~ Effect"}}Effect{{/crossLink}} data.
 *
 * @property effects
 * @type Array
 */

/**
 * The item type ID.
 *
 * @property itypeId
 * @type Number
 */

/**
 * The price.
 *
 * @property price
 * @type Number
 */

/**
 * The consume flag.
 *
 * @property consumable
 * @type Boolean
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a weapon.
 *
 * @class ~ Weapon
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The description.
 *
 * @property description
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The index of the icon image.
 *
 * @property iconIndex
 * @type Number
 */

/**
 * The array of {{#crossLink "~ Trait"}}Trait{{/crossLink}} data.
 *
 * @property traits
 * @type Array
 */

/**
 * The equipment type ID.
 *
 * @property etypeId
 * @type Number
 */

/**
 * The price.
 *
 * @property price
 * @type Number
 */

/**
 * The parameter changes.
 *
 * @property params
 * @type Array
 */

/**
 * The weapon type ID.
 *
 * @property wtypeId
 * @type Number
 */

/**
 * The animation ID.
 *
 * @property animationId
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an armor.
 *
 * @class ~ Armor
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The description.
 *
 * @property description
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The index of the icon image.
 *
 * @property iconIndex
 * @type Number
 */

/**
 * The array of {{#crossLink "~ Trait"}}Trait{{/crossLink}} data.
 *
 * @property traits
 * @type Array
 */

/**
 * The equipment type ID.
 *
 * @property etypeId
 * @type Number
 */

/**
 * The price.
 *
 * @property price
 * @type Number
 */

/**
 * The parameter changes.
 *
 * @property params
 * @type Array
 */

/**
 * The armor type ID.
 *
 * @property atypeId
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a trait.
 *
 * @class ~ Trait
 */

/**
 * The trait code.
 *
 * @property code
 * @type Number
 */

/**
 * The ID of the data (such as elements or states) according to the type of the trait.
 *
 * @property dataId
 * @type Number
 */

/**
 * The value set according to the type of the trait.
 *
 * @property value
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an effect.
 *
 * @class ~ Effect
 */

/**
 * The effect code.
 *
 * @property code
 * @type Number
 */

/**
 * The ID of the data (such as elements or states) according to the type of the effect.
 *
 * @property dataId
 * @type Number
 */

/**
 * The value 1 set according to the type of the effect.
 *
 * @property value1
 * @type Number
 */

/**
 * The value 2 set according to the type of the effect.
 *
 * @property value2
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a damage.
 *
 * @class ~ Damage
 */

/**
 * The type.
 *
 * @property type
 * @type Number
 */

/**
 * The element ID.
 *
 * @property elementId
 * @type Number
 */

/**
 * The formula.
 *
 * @property formula
 * @type String
 */

/**
 * The variance.
 *
 * @property variance
 * @type Number
 */

/**
 * The critical hit flag.
 *
 * @property critical
 * @type Boolean
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an enemy.
 *
 * @class ~ Enemy
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The array of {{#crossLink "~ Trait"}}Trait{{/crossLink}} data.
 *
 * @property traits
 * @type Array
 */

/**
 * The file name of the enemy image.
 *
 * @property battlerName
 * @type String
 */

/**
 * The hue rotation value of the enemy image.
 *
 * @property battlerHue
 * @type Number
 */

/**
 * The parameters.
 *
 * @property params
 * @type Array
 */

/**
 * The EXP.
 *
 * @property exp
 * @type Number
 */

/**
 * The gold.
 *
 * @property gold
 * @type Number
 */

/**
 * The array of {{#crossLink "~ Enemy::DropItem"}}DropItem{{/crossLink}} data.
 *
 * @property dropItems
 * @type Array
 */

/**
 * The array of {{#crossLink "~ Enemy::Action"}}Action{{/crossLink}} data.
 *
 * @property actions
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a drop item.
 *
 * @class ~ Enemy::DropItem
 */

/**
 * The kind of the item.
 *
 * @property kind
 * @type Number
 */

/**
 * The ID of the data depending on the kind.
 *
 * @property dataId
 * @type Number
 */

/**
 * The N of the probability 1/N that the item will be dropped.
 *
 * @property denominator
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an enemy action.
 *
 * @class ~ Enemy::Action
 */

/**
 * The skill ID.
 *
 * @property skillId
 * @type Number
 */

/**
 * The condition type.
 *
 * @property conditionType
 * @type Number
 */

/**
 * The first parameter of the condition.
 *
 * @property conditionParam1
 * @type Number
 */

/**
 * The second parameter of the condition.
 *
 * @property conditionParam2
 * @type Number
 */

/**
 * The rating.
 *
 * @property rating
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a state.
 *
 * @class ~ State
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The index of the icon image.
 *
 * @property iconIndex
 * @type Number
 */

/**
 * The array of {{#crossLink "~ Trait"}}Trait{{/crossLink}} data.
 *
 * @property traits
 * @type Array
 */

/**
 * The restriction.
 *
 * @property restriction
 * @type Number
 */

/**
 * The priority.
 *
 * @property priority
 * @type Number
 */

/**
 * The side-view motion.
 *
 * @property motion
 * @type Number
 */

/**
 * The side-view overlay.
 *
 * @property overlay
 * @type Number
 */

/**
 * The "remove at battle end" option.
 *
 * @property removeAtBattleEnd
 * @type Boolean
 */

/**
 * The "remove by restriction" option.
 *
 * @property removeByRestriction
 * @type Boolean
 */

/**
 * The auto-removal timing.
 *
 * @property autoRemovalTiming
 * @type Number
 */

/**
 * The minimum turns of the duration.
 *
 * @property minTurns
 * @type Number
 */

/**
 * The maximum turns of the duration.
 *
 * @property maxTurns
 * @type Number
 */

/**
 * The "remove by damage" option.
 *
 * @property removeByDamage
 * @type Boolean
 */

/**
 * The probability of the state removal by damage.
 *
 * @property chanceByDamage
 * @type Number
 */

/**
 * The "remove by walking" option.
 *
 * @property removeByWalking
 * @type Boolean
 */

/**
 * The number of steps until the state is removed.
 *
 * @property stepsToRemove
 * @type Number
 */

/**
 * The message when an actor fell in the state.
 *
 * @property message1
 * @type String
 */

/**
 * The message when an enemy fell in the state.
 *
 * @property message2
 * @type String
 */

/**
 * The message when the state remains.
 *
 * @property message3
 * @type String
 */

/**
 * The message when the state is removed.
 *
 * @property message4
 * @type String
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a troop.
 *
 * @class ~ Troop
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The array of {{#crossLink "~ Troop::Member"}}Member{{/crossLink}} data.
 *
 * @property members
 * @type Array
 */

/**
 * The array of {{#crossLink "~ BattleEventPage"}}BattleEventPage{{/crossLink}} data.
 *
 * @property pages
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a troop member.
 *
 * @class ~ Troop::Member
 */

/**
 * The enemy ID.
 *
 * @property enemyId
 * @type Number
 */

/**
 * The x coordinate.
 *
 * @property x
 * @type Number
 */

/**
 * The y coordinate.
 *
 * @property y
 * @type Number
 */

/**
 * The "appear halfway" option.
 *
 * @property hidden
 * @type Boolean
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an animation.
 *
 * @class ~ Animation
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The file name of the first animation image.
 *
 * @property animation1Name
 * @type String
 */

/**
 * The hue rotation value of the first animation image.
 *
 * @property animation1Hue
 * @type Number
 */

/**
 * The file name of the second animation image.
 *
 * @property animation2Name
 * @type String
 */

/**
 * The hue rotation value of the second animation image.
 *
 * @property animation2Hue
 * @type Number
 */

/**
 * The position.
 *
 * @property position
 * @type Number
 */

/**
 * The three-dimensional array containing the frame contents.
 *
 * @property frames
 * @type Array
 */

/**
 * The array of {{#crossLink "~ Animation::Timing"}}Timing{{/crossLink}} data.
 *
 * @property timings
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a timing of SE and flash effects in animations.
 *
 * @class ~ Animation::Timing
 */

/**
 * The frame index.
 *
 * @property frame
 * @type Number
 */

/**
 * The SE.
 *
 * @property se
 * @type ~ AudioFile
 */

/**
 * The flash scope.
 *
 * @property flashScope
 * @type Number
 */

/**
 * The flash color.
 *
 * @property flashColor
 * @type Array
 */

/**
 * The flash duration.
 *
 * @property flashDuration
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a tileset.
 *
 * @class ~ Tileset
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The mode.
 *
 * @property mode
 * @type Number
 */

/**
 * The file names of the tileset images.
 *
 * @property tilesetNames
 * @type Array
 */

/**
 * The tile flags.
 *
 * @property flags
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a common event.
 *
 * @class ~ CommonEvent
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The trigger.
 *
 * @property trigger
 * @type Number
 */

/**
 * The switch ID.
 *
 * @property switchId
 * @type Number
 */

/**
 * The array of {{#crossLink "~ EventCommand"}}EventCommand{{/crossLink}} data.
 *
 * @property list
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of the system.
 *
 * @class ~ System
 */

/**
 * The game title.
 *
 * @property gameTitle
 * @type String
 */

/**
 * The random number used for update check.
 *
 * @property versionId
 * @type Number
 */

/**
 * The locale string such as "ja_JP" and "en_US".
 *
 * @property locale
 * @type String
 */

/**
 * The array of actor IDs in the starting party.
 *
 * @property partyMembers
 * @type Array
 */

/**
 * The currency unit.
 *
 * @property currencyUnit
 * @type String
 */

/**
 * The window color.
 *
 * @property windowTone
 * @type Array
 */

/**
 * The boat settings.
 *
 * @property boat
 * @type ~ System::Vehicle
 */

/**
 * The ship settings.
 *
 * @property ship
 * @type ~ System::Vehicle
 */

/**
 * The airship settings.
 *
 * @property airship
 * @type ~ System::Vehicle
 */

/**
 * The file name of the background image in the title screen.
 *
 * @property title1Name
 * @type String
 */

/**
 * The file name of the frame image in the title screen.
 *
 * @property title2Name
 * @type String
 */

/**
 * The "draw game title" option.
 *
 * @property optDrawTitle
 * @type Boolean
 */

/**
 * The "use side-view battle" option.
 *
 * @property optSideView
 * @type Boolean
 */

/**
 * The "start transparent" option.
 *
 * @property optTransparent
 * @type Boolean
 */

/**
 * The "show player followers" option.
 *
 * @property optFollowers
 * @type Boolean
 */

/**
 * The "knockout by slip damage" option.
 *
 * @property optSlipDeath
 * @type Boolean
 */

/**
 * The "knockout by floor damage" option.
 *
 * @property optFloorDeath
 * @type Boolean
 */

/**
 * The "display TP in battle" option.
 *
 * @property optDisplayTp
 * @type Boolean
 */

/**
 * The "EXP for reserve members" option.
 *
 * @property optExtraExp
 * @type Boolean
 */

/**
 * The title BGM.
 *
 * @property titleBgm
 * @type ~ AudioFile
 */

/**
 * The battle BGM.
 *
 * @property battleBgm
 * @type ~ AudioFile
 */

/**
 * The victory ME.
 *
 * @property victoryMe
 * @type ~ AudioFile
 */

/**
 * The defeat ME.
 *
 * @property defeatMe
 * @type ~ AudioFile
 */

/**
 * The game over ME.
 *
 * @property gameoverMe
 * @type ~ AudioFile
 */

/**
 * The array of {{#crossLink "~ AudioFile"}}AudioFile{{/crossLink}} data
 * for sound effects.
 *
 * @property sounds
 * @type Array
 */

/**
 * The menu commands.
 *
 * @property menuCommands
 * @type Array
 */

/**
 * The map ID of the player's starting position.
 *
 * @property startMapId
 * @type Number
 */

/**
 * The x coordinate of the player's starting position.
 *
 * @property startX
 * @type Number
 */

/**
 * The y coordinate of the player's starting position.
 *
 * @property startY
 * @type Number
 */

/**
 * The array of skill type IDs for magic skills.
 *
 * @property magicSkills
 * @type Array
 */

/**
 * The array of {{#crossLink "~ System::AttackMotion"}}AttackMotion{{/crossLink}} data.
 *
 * @property attackMotions
 * @type Array
 */

/**
 * The list of the elements.
 *
 * @property elements
 * @type Array
 */

/**
 * The list of the skill types.
 *
 * @property skillTypes
 * @type Array
 */

/**
 * The list of the weapon types.
 *
 * @property weaponTypes
 * @type Array
 */

/**
 * The list of the armor types.
 *
 * @property armorTypes
 * @type Array
 */

/**
 * The list of the equipment types.
 *
 * @property equipTypes
 * @type Array
 */

/**
 * The list of the switch names.
 *
 * @property switches
 * @type Array
 */

/**
 * The list of the variable names.
 *
 * @property variables
 * @type Array
 */

/**
 * The terms.
 *
 * @property terms
 * @type ~ System::Terms
 */

/**
 * The array of {{#crossLink "~ System::TestBattler"}}TestBattler{{/crossLink}} data.
 *
 * @property testBattlers
 * @type Array
 */

/**
 * The troop ID for battle tests.
 *
 * @property testTroopId
 * @type Number
 */

/**
 * The file name of the battle background floor image for battle tests.
 *
 * @property battleback1Name
 * @type String
 */

/**
 * The file name of the battle background wall image for battle tests.
 *
 * @property battleback2Name
 * @type String
 */

/**
 * The file name of the enemy image for use in editing animations.
 *
 * @property battlerName
 * @type String
 */

/**
 * The hue rotation value of the enemy image for use in editing animations.
 *
 * @property battlerHue
 * @type Number
 */

/**
 * The ID of the map currently being edited.
 *
 * @property editMapId
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a vehicle.
 *
 * @class ~ System::Vehicle
 */

/**
 * The file name of the character image.
 *
 * @property characterName
 * @type String
 */

/**
 * The index of the character image.
 *
 * @property characterIndex
 * @type Number
 */

/**
 * The map ID of the starting position.
 *
 * @property startMapId
 * @type Number
 */

/**
 * The x coordinate of the starting position.
 *
 * @property startX
 * @type Number
 */

/**
 * The y coordinate of the starting position.
 *
 * @property startY
 * @type Number
 */

/**
 * The BGM.
 *
 * @property bgm
 * @type ~ AudioFile
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an attack motion.
 *
 * @class ~ System::AttackMotion
 */

/**
 * The type of the motion.
 *
 * @property type
 * @type Number
 */

/**
 * The ID of the weapon image.
 *
 * @property weaponImageId
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an actor used in battle tests.
 *
 * @class ~ System::TestBattler
 */

/**
 * The actor ID.
 *
 * @property actorId
 * @type Number
 */

/**
 * The level.
 *
 * @property level
 * @type Number
 */

/**
 * The equipment.
 *
 * @property equips
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of the terminology.
 *
 * @class ~ System::Terms
 */

/**
 * The basic status names.
 *
 * @property basic
 * @type Array
 */

/**
 * The parameter names.
 *
 * @property params
 * @type Array
 */

/**
 * The command names.
 *
 * @property commands
 * @type Array
 */

/**
 * The messages.
 *
 * @property messages
 * @type Object
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an audio file.
 *
 * @class ~ AudioFile
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The volume.
 *
 * @property volume
 * @type Number
 */

/**
 * The pitch.
 *
 * @property pitch
 * @type Number
 */

/**
 * The pan.
 *
 * @property pan
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a map.
 *
 * @class ~ Map
 */

/**
 * The display name.
 *
 * @property displayName
 * @type String
 */

/**
 * The tileset ID.
 *
 * @property tilesetId
 * @type Number
 */

/**
 * The width.
 *
 * @property width
 * @type Number
 */

/**
 * The height.
 *
 * @property height
 * @type Number
 */

/**
 * The scroll type.
 *
 * @property scrollType
 * @type Number
 */

/**
 * The "specify battleback" option.
 *
 * @property specifyBattleback
 * @type Boolean
 */

/**
 * The file name of the battle background floor image.
 *
 * @property battleback1Name
 * @type String
 */

/**
 * The file name of the battle background wall image.
 *
 * @property battleback2Name
 * @type String
 */

/**
 * The "autoplay BGM" option.
 *
 * @property autoplayBgm
 * @type Boolean
 */

/**
 * The BGM for autoplay.
 *
 * @property bgm
 * @type ~ AudioFile
 */

/**
 * The "autoplay BGS" option.
 *
 * @property autoplayBgs
 * @type Boolean
 */

/**
 * The BGS for autoplay.
 *
 * @property bgs
 * @type ~ AudioFile
 */

/**
 * The "disable dashing" option.
 *
 * @property disableDashing
 * @type Boolean
 */

/**
 * The array of {{#crossLink "~ Map::Encounter"}}Encounter{{/crossLink}} data.
 *
 * @property encounterList
 * @type Array
 */

/**
 * The encounter steps.
 *
 * @property encounterStep
 * @type Number
 */

/**
 * The file name of the parallax background image.
 *
 * @property parallaxName
 * @type String
 */

/**
 * The "loop horizontally" option for the parallax background.
 *
 * @property parallaxLoopX
 * @type Boolean
 */

/**
 * The "loop vertically" option for the parallax background.
 *
 * @property parallaxLoopY
 * @type Boolean
 */

/**
 * The automatic x-axis scrolling speed for the parallax background.
 *
 * @property parallaxSx
 * @type Number
 */

/**
 * The automatic y-axis scrolling speed for the parallax background.
 *
 * @property parallaxSy
 * @type Number
 */

/**
 * The "show in the editor" option for the parallax background.
 *
 * @property parallaxShow
 * @type Boolean
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The three-dimensional array containing the map data.
 *
 * @property data
 * @type Array
 */

/**
 * The array of {{#crossLink "~ Event"}}Event{{/crossLink}} data.
 *
 * @property events
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an encounter setting.
 *
 * @class ~ Map::Encounter
 */

/**
 * The troop ID.
 *
 * @property troopId
 * @type Number
 */

/**
 * The weight.
 *
 * @property weight
 * @type Number
 */

/**
 * The array of region IDs.
 *
 * @property regionSet
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a map information.
 *
 * @class ~ MapInfo
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The parent map ID.
 *
 * @property parentId
 * @type Number
 */

/**
 * The order in the map tree.
 *
 * @property order
 * @type Number
 */

/**
 * The expansion flag in the map tree.
 *
 * @property expanded
 * @type Boolean
 */

/**
 * The horizontal scroll position in the map editor.
 *
 * @property scrollX
 * @type Boolean
 */

/**
 * The vertical scroll position in the map editor.
 *
 * @property scrollY
 * @type Boolean
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a map event.
 *
 * @class ~ Event
 */

/**
 * The ID.
 *
 * @property id
 * @type Number
 */

/**
 * The name.
 *
 * @property name
 * @type String
 */

/**
 * The note.
 *
 * @property note
 * @type String
 */

/**
 * The x coordinate.
 *
 * @property x
 * @type Number
 */

/**
 * The y coordinate.
 *
 * @property y
 * @type Number
 */

/**
 * The array of {{#crossLink "~ EventPage"}}EventPage{{/crossLink}} data.
 *
 * @property pages
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an event page.
 *
 * @class ~ EventPage
 */

/**
 * The conditions.
 *
 * @property conditions
 * @type ~ EventPage::Conditions
 */

/**
 * The image.
 *
 * @property image
 * @type ~ EventPage::Image
 */

/**
 * The movement type.
 *
 * @property moveType
 * @type Number
 */

/**
 * The movement speed.
 *
 * @property moveSpeed
 * @type Number
 */

/**
 * The movement frequency.
 *
 * @property moveFrequency
 * @type Number
 */

/**
 * The movement route.
 *
 * @property moveRoute
 * @type ~ MoveRoute
 */

/**
 * The "walking" option.
 *
 * @property walkAnime
 * @type Boolean
 */

/**
 * The "stepping" option.
 *
 * @property stepAnime
 * @type Boolean
 */

/**
 * The "direction fix" option.
 *
 * @property directionFix
 * @type Boolean
 */

/**
 * The "through" option.
 *
 * @property through
 * @type Boolean
 */

/**
 * The priority.
 *
 * @property priorityType
 * @type Number
 */

/**
 * The trigger.
 *
 * @property trigger
 * @type Number
 */

/**
 * The array of {{#crossLink "~ EventCommand"}}EventCommand{{/crossLink}} data.
 *
 * @property list
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of conditions for a map event.
 *
 * @class ~ EventPage::Conditions
 */

/**
 * The boolean value indicating whether the first "switch" is valid.
 *
 * @property switch1Valid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the second "switch" is valid.
 *
 * @property switch2Valid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "variable" is valid.
 *
 * @property variableValid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "self switch" is valid.
 *
 * @property selfSwitchValid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "item" is valid.
 *
 * @property itemValid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "actor" is valid.
 *
 * @property actorValid
 * @type Boolean
 */

/**
 * The first switch ID.
 *
 * @property switch1Id
 * @type Number
 */

/**
 * The second switch ID.
 *
 * @property switch2Id
 * @type Number
 */

/**
 * The variable ID.
 *
 * @property variableId
 * @type Number
 */

/**
 * The refrence value for the variable.
 *
 * @property variableValue
 * @type Number
 */

/**
 * The letter of the self switch.
 *
 * @property selfSwitchCh
 * @type String
 */

/**
 * The item ID.
 *
 * @property itemId
 * @type Number
 */

/**
 * The actor ID.
 *
 * @property actorId
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an event image.
 *
 * @class ~ EventPage::Image
 */

/**
 * The tile ID.
 *
 * @property tileId
 * @type Number
 */

/**
 * The file name of the character image.
 *
 * @property characterName
 * @type String
 */

/**
 * The index of the character image.
 *
 * @property characterIndex
 * @type Number
 */

/**
 * The direction.
 *
 * @property direction
 * @type Number
 */

/**
 * The pattern index.
 *
 * @property pattern
 * @type Number
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a movement route.
 *
 * @class ~ MoveRoute
 */

/**
 * The "repeat movements" option.
 *
 * @property repeat
 * @type Boolean
 */

/**
 * The "skip if cannot move" option.
 *
 * @property skippable
 * @type Boolean
 */

/**
 * The "wait for completion" option.
 *
 * @property wait
 * @type Boolean
 */

/**
 * The array of {{#crossLink "~ MoveCommand"}}MoveCommand{{/crossLink}} data.
 *
 * @property list
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a movement command.
 *
 * @class ~ MoveCommand
 */

/**
 * The command code.
 *
 * @property code
 * @type Number
 */

/**
 * The parameters according to the command code.
 *
 * @property parameters
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of an event command.
 *
 * @class ~ EventCommand
 */

/**
 * The command code.
 *
 * @property code
 * @type Number
 */

/**
 * The indent depth.
 *
 * @property indent
 * @type Number
 */

/**
 * The parameters according to the command code.
 *
 * @property parameters
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of a battle event page.
 *
 * @class ~ BattleEventPage
 */

/**
 * The conditions.
 *
 * @property conditions
 * @type ~ BattleEventPage::Conditions
 */

/**
 * The span.
 *
 * @property span
 * @type Number
 */

/**
 * The array of {{#crossLink "~ EventCommand"}}EventCommand{{/crossLink}} data.
 *
 * @property list
 * @type Array
 */

//-----------------------------------------------------------------------------
/**
 * The JSON data structure of conditions for a battle event.
 *
 * @class ~ BattleEventPage::Conditions
 */

/**
 * The boolean value indicating whether the "turn end" is valid.
 *
 * @property turnEnding
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "turn" is valid.
 *
 * @property turnValid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "enemy HP" is valid.
 *
 * @property enemyValid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "actor HP" is valid.
 *
 * @property actorValid
 * @type Boolean
 */

/**
 * The boolean value indicating whether the "switch" is valid.
 *
 * @property switchValid
 * @type Boolean
 */

/**
 * The turn condition value A.
 *
 * @property turnA
 * @type Number
 */

/**
 * The turn condition value B.
 *
 * @property turnB
 * @type Number
 */

/**
 * The enemy index.
 *
 * @property enemyIndex
 * @type Number
 */

/**
 * The percentage of enemy HP.
 *
 * @property enemyHp
 * @type Number
 */

/**
 * The actor ID.
 *
 * @property actorId
 * @type Number
 */

/**
 * The percentage of actor HP.
 *
 * @property actorHp
 * @type Number
 */

/**
 * The switch ID.
 *
 * @property switchId
 * @type Number
 */

//-----------------------------------------------------------------------------

package com.focusmate.proactive

/** 随机源。测试注入固定实现，生产用真随机。 */
interface RandomSource {
    /** [0,1) 均匀分布。 */
    fun nextDouble(): Double
}

/** 生产用真随机。 */
class SystemRandomSource : RandomSource {
    private val rng = java.util.Random()
    override fun nextDouble(): Double = rng.nextDouble()
}

/** 确定性随机源。同一种子给出同一串数，便于复现与验收「不同种子产生不同分布」。 */
class SeededRandomSource(seed: Long) : RandomSource {
    private val rng = java.util.Random(seed)
    override fun nextDouble(): Double = rng.nextDouble()
}

package com.lumacam.core.capture

/**
 * Orden de intentos al abrir la cámara cuando no todas las funciones pedidas caben juntas
 * (p. ej. look en vista previa + estabilización + detección de pose): primero todas, luego se va
 * renunciando a las menos importantes.
 */
object BindAttempts {
    /**
     * @param features funciones pedidas con su prioridad (mayor = más importante; usar valores
     *   distintos y potencias de 2 para que una función importante pese más que todas las demás).
     * @return subconjuntos de [features] del mejor al peor; el último es el vacío.
     */
    fun <T> ordered(features: Map<T, Int>): List<Set<T>> {
        val list = features.keys.toList()
        val n = list.size
        val subsets = (0 until (1 shl n)).map { mask ->
            list.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
        }
        return subsets.sortedWith(
            compareByDescending<Set<T>> { s -> s.sumOf { features.getValue(it) } }.thenByDescending { it.size },
        )
    }
}

package com.bipss.app.domain

import org.junit.Assert.*
import org.junit.Test

class FitnessEstimateTest {
    private val input = FitnessInput(40, 180.0, 81.0, 4, FitnessReference.MALE)
    @Test fun publishedEquationAndSexCoefficient() {
        val male = FitnessEstimate.calculate(input, 1)!!
        assertEquals(40.944, male.vo2, 0.00001)
        val female = FitnessEstimate.calculate(input.copy(reference = FitnessReference.FEMALE), 1)!!
        assertEquals(10.987, male.vo2 - female.vo2, 0.00001)
    }
    @Test fun measurementsAndActivityActuallyChangeEstimate() {
        val base = FitnessEstimate.calculate(input, 1)!!.vo2
        assertTrue(FitnessEstimate.calculate(input.copy(weightKg = 90.0), 1)!!.vo2 < base)
        assertEquals(1.921, FitnessEstimate.calculate(input.copy(activityRating = 5), 1)!!.vo2 - base, 0.00001)
    }
    @Test fun refusesMissingOrOutOfRangeInputs() {
        listOf(input.copy(age=19), input.copy(age=71), input.copy(heightCm=Double.NaN),
            input.copy(weightKg=Double.POSITIVE_INFINITY), input.copy(activityRating=8),
            input.copy(heightCm=120.0, weightKg=200.0)).forEach { assertNull(FitnessEstimate.calculate(it, 1)) }
    }
    @Test fun ageReferenceUsesPublishedDecadesAndDoesNotExtrapolate() {
        assertEquals("50–59", FitnessEstimate.ageBand(42.0, FitnessReference.MALE))
        assertEquals("30–39", FitnessEstimate.ageBand(40.0, FitnessReference.FEMALE))
        assertEquals("20–29 or younger", FitnessEstimate.ageBand(60.0, FitnessReference.MALE))
        assertEquals("70+", FitnessEstimate.ageBand(20.0, FitnessReference.FEMALE))
    }
}

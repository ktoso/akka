package scala

import org.openjdk.jmh.annotations.Benchmark

class ArraysBenchmark {

  @Benchmark
  def create_ofDim: Array[Byte] =
    Array.ofDim[Byte](32)

  @Benchmark
  def create_new: Array[Byte] =
    new Array[Byte](32)

}

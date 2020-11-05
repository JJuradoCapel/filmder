package org.apache.spark.mllib.recommendation
import com.github.fommil.netlib.BLAS.{getInstance => blas}
// This must be the same package as Spark's MatrixFactorizationModel because
// MatrixFactorizationModel's constructor is private and we are using
// its constructor in order to save and load the model

import org.example.recommendation.ALSAlgorithmParams

import org.apache.predictionio.controller.PersistentModel
import org.apache.predictionio.controller.PersistentModelLoader
import org.apache.predictionio.data.storage.BiMap

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

class ALSModel(
    override val rank: Int,
    override val userFeatures: RDD[(Int, Array[Double])],
    override val productFeatures: RDD[(Int, Array[Double])],
    val userStringIntMap: BiMap[String, Int],
    val itemStringIntMap: BiMap[String, Int])
  extends MatrixFactorizationModel(rank, userFeatures, productFeatures)
  with PersistentModel[ALSAlgorithmParams] {

  def save(id: String, params: ALSAlgorithmParams,
    sc: SparkContext): Boolean = {

    sc.parallelize(Seq(rank)).saveAsObjectFile(s"/tmp/${id}/rank")
    userFeatures.saveAsObjectFile(s"/tmp/${id}/userFeatures")
    productFeatures.saveAsObjectFile(s"/tmp/${id}/productFeatures")
    sc.parallelize(Seq(userStringIntMap))
      .saveAsObjectFile(s"/tmp/${id}/userStringIntMap")
    sc.parallelize(Seq(itemStringIntMap))
      .saveAsObjectFile(s"/tmp/${id}/itemStringIntMap")
    true
  }

  override def toString = {
    s"userFeatures: [${userFeatures.count()}]" +
    s"(${userFeatures.take(2).toList}...)" +
    s" productFeatures: [${productFeatures.count()}]" +
    s"(${productFeatures.take(2).toList}...)" +
    s" userStringIntMap: [${userStringIntMap.size}]" +
    s"(${userStringIntMap.take(2)}...)" +
    s" itemStringIntMap: [${itemStringIntMap.size}]" +
    s"(${itemStringIntMap.take(2)}...)"
  }

  def recommendProductsWithFilter(user: Int, num: Int, productIdFilter: Set[Int]) = {
    val filteredProductFeatures = productFeatures
      .filter { case (id, _) => !productIdFilter.contains(id) } // (*)
    recommend(userFeatures.lookup(user).head, filteredProductFeatures, num)
      .map(t => Rating(user, t._1, t._2))
  }

  private def recommend(
      recommendToFeatures: Array[Double],
      recommendableFeatures: RDD[(Int, Array[Double])],
      num: Int): Array[(Int, Double)] = {
    val scored = recommendableFeatures.map { case (id, features) =>
      (id, blas.ddot(features.length, recommendToFeatures, 1, features, 1))
    }
    scored.top(num)(Ordering.by(_._2))
  }
}

object ALSModel
  extends PersistentModelLoader[ALSAlgorithmParams, ALSModel] {
  def apply(id: String, params: ALSAlgorithmParams,
    sc: Option[SparkContext]) = {
    new ALSModel(
      rank = sc.get.objectFile[Int](s"/tmp/${id}/rank").first,
      userFeatures = sc.get.objectFile(s"/tmp/${id}/userFeatures"),
      productFeatures = sc.get.objectFile(s"/tmp/${id}/productFeatures"),
      userStringIntMap = sc.get
        .objectFile[BiMap[String, Int]](s"/tmp/${id}/userStringIntMap").first,
      itemStringIntMap = sc.get
        .objectFile[BiMap[String, Int]](s"/tmp/${id}/itemStringIntMap").first)
  }
}

package com.funcoming.lihai

import java.sql.DriverManager

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.{Accumulator, SparkConf, SparkContext}

/**
  * Created by LiuFangGuo on 5/8/17.
  */
object SMSAppendixTemp {

  def main(args: Array[String]): Unit = {
    start(args(0), args(1))
  }


  def getReferenceMap(): Map[String, (String, Integer, String, String, String, Integer, Integer)] = {
    val driverName = "org.postgresql.Driver"
    val connectionUrl = "jdbc:postgresql://192.168.12.14:5432/tjdw"
    val username = "tj_root"
    val password = "77pbV1YU!T"
    Class.forName(driverName)
    val connectionInstance = DriverManager.getConnection(connectionUrl, username, password)
    val statementInstance = connectionInstance.createStatement()
    val sqlQuery = "select charge_code_id,service_provider_id,service_provider_name,dest_number,dest_number_code,price,charge_code_name,sms_business_text from bdl.honeycomb_sms_bridge_business_temp"
    val resultSetInstance = statementInstance.executeQuery(sqlQuery)
    var referenceTableMap = scala.collection.mutable.Map[String, Tuple7[String, Integer, String, String, String, Integer, Integer]]()
    while (resultSetInstance.next()) {
      if (!referenceTableMap.contains(resultSetInstance.getString("sms_business_text"))) {
        referenceTableMap(resultSetInstance.getString("sms_business_text")) = Tuple7(resultSetInstance.getString("charge_code_name"), resultSetInstance.getInt("price"), resultSetInstance.getString("dest_number_code"), resultSetInstance.getString("dest_number"), resultSetInstance.getString("service_provider_name"), resultSetInstance.getInt("service_provider_id"), resultSetInstance.getInt("charge_code_id"))
      }
    }
    val tempMap = referenceTableMap.toSeq.sortBy(_._1.length).reverse.toMap
    //    for ((k, v) <- tempMap) printf("这是在主函数中key: %s, value: %s\n", k, v)
    return tempMap
  }


  def getBusinessCode(bc: Broadcast[scala.collection.immutable.Map[String, (String, Integer, String, String, String, Integer, Integer)]], content: String): String = {
    if (content == null) {
      return "\\N|\\N|\\N|\\N|\\N|\\N|\\N|\\N"
    }
    if (content.length < 3) {
      return "\\N|\\N|\\N|\\N|\\N|\\N|\\N|\\N"
    }
    for (businessCode <- bc.value.keys) {
      if (content.contains(businessCode)) {
        val maybeTuple: Option[(String, Integer, String, String, String, Integer, Integer)] = bc.value.get(businessCode)
        val tuple: (String, Integer, String, String, String, Integer, Integer) = maybeTuple.get
        return businessCode + "|" + tuple.productIterator.mkString("|")
      }
    }
    return "\\N|\\N|\\N|\\N|\\N|\\N|\\N|\\N"
  }


  def getStatus(businessCode: String, content: String): String = {
    if (businessCode.equals("\\N|\\N|\\N|\\N|\\N|\\N|\\N|\\N")) {
      return "\\N"
    }
    /*
 *在总结订购成功短信语句的特点。。
   1.这是第一类		（已）【32】（订购|定制）【32】（business_code） 大于32就算异常。。。
   2.				（订购|定制）（business_code）（即将扣费）
  */
    val businessArray: Array[String] = businessCode.split("[|]")
    val businessKey = businessArray(0)
    val gap = 31
    val businessIndex = content.indexOf(businessKey)
    val contentPreSub = content.substring(0, businessIndex)
    var dingMax = -1
    val dinggou = ("订购", "定制", "订制", "办理")
    dinggou.productIterator.foreach {
      ele => {
        var tempIndex = contentPreSub.lastIndexOf("" + ele)
        if (tempIndex > dingMax) {
          dingMax = tempIndex
        }
      }
    }
    //没有或者大于31，就是有问题的
    if (dingMax == -1 || businessIndex - dingMax > gap) {
      return "\\N"
    }
    //这是第一类情况
    val dingSub = contentPreSub.substring(0, dingMax)
    val yiIndex = dingSub.lastIndexOf("已")
    if (yiIndex > -1 && dingMax - yiIndex < gap + 1) {
      return "ok"
    }
    //第二类情况
    val contentPstSub = content.substring(businessIndex + businessKey.length, content.length)
    if (contentPstSub.contains("即将扣费")) {
      return "ok"
    }
    return "\\N"
  }


  def allTheCompletion(line: String, bc: Broadcast[scala.collection.immutable.Map[String, (String, Integer, String, String, String, Integer, Integer)]], lnc: Accumulator[Long]): String = {
    //    for ((k, v) <- bc.value) printf("这是在Executor中，也就是每个Map中key: %s, value: %s\n", k, v)
    lnc.add(1)
    val splitArray: Array[String] = line.split("[|]")
    //    val sc = splitArray(10)
    //    val imsi = splitArray(4)
    val content = splitArray(2)
    //    val location = MainApp.getLocation(sc, imsi)
    val md5Str = splitArray(28)
    val businessCode = getBusinessCode(bc, content)
    //    print("业务码是" + businessCode)
    val status = getStatus(businessCode, content)
    //    println("--------" + status)
    val appendixLine = splitArray(0) + "|" + splitArray(1) + "|" + splitArray(2) + "|" + splitArray(3) + "|" + splitArray(4) + "|" + splitArray(5) + "|" + splitArray(6) + "|" + splitArray(7) + "|" + splitArray(8) + "|" + splitArray(9) + "|" + splitArray(10) + "|" + splitArray(11) + "|" + splitArray(12) + "|" + splitArray(13) + "|" + splitArray(14) + "|" + splitArray(15) + "|" + splitArray(16) + "|" + splitArray(17) + "|" + splitArray(18) + "|" + splitArray(19)
    return appendixLine + "|" + businessCode + "|" + md5Str + "|" + status
  }

  def start(inputpath: String, outputpaath: String): Unit = {
    val conf = new SparkConf().setAppName("短信附加信息添加")
    //    val conf = new SparkConf().setAppName("短信附加信息添加").setMaster("local[*]")
    val sparkContext = new SparkContext(conf)
    val bc = sparkContext.broadcast(getReferenceMap())
    val lnc = sparkContext.accumulator(0L, "LineNumberCounter")
    val inputlinesRDD = sparkContext.textFile(inputpath)
    inputlinesRDD.map(line => allTheCompletion(line, bc, lnc)).coalesce(1, true).saveAsTextFile(outputpaath)
    println("一共处理了%d行", lnc.value)
  }


}

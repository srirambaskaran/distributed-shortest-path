import java.util.Calendar

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx._

import scala.io.Source

class ShortestPaths(val paths: Map[VertexId, (Set[VertexId], Double)]) extends Serializable{
    override def toString: String = paths.toString()
}

class ForwardMessage(val start: VertexId, val from: Set[VertexId], val cost: Double) extends Serializable{
    override def toString: String = "start="+start+", from="+from+", cost="+cost
}

class ForwardMessages(val messages: Set[ForwardMessage]) extends Serializable{
    override def toString: String = messages.mkString(", ")
}


class APSP extends Serializable{
    def unweightedFlyodWarshall(graph: Graph[Int, Char], maxIter: Int): Graph[ShortestPaths, Char] ={

        val initialGraph = graph.mapVertices(
            (x, _) => new ShortestPaths(Map(x  -> (Set[VertexId](), 0.0)) )).cache()

        println("Initial Graph created")

        def vertexUpdate(id: VertexId, vertex: ShortestPaths, forwardMessages: ForwardMessages): ShortestPaths = {
            val currentPaths = vertex.paths

            val partition = forwardMessages.messages.partition(x => currentPaths.contains(x.start))
            val notInMessagesId = partition._2.map(x => (x.start, x.from, x.cost))


            val costPartition = partition._1.partition(x => x.cost <= currentPaths(x.start)._2)
            val retainSet = costPartition._2.map(x => (x.start, currentPaths(x.start)._1, currentPaths(x.start)._2))

            val equalPartition = costPartition._1.partition(x=> x.cost == currentPaths(x.start)._2)

            val equals = equalPartition._1.map(x => (x.start, currentPaths(x.start)._1 ++ x.from, x.cost))
            val strictlyLessThan = equalPartition._2.map(x => (x.start, x.from, x.cost))

            val updates = currentPaths ++ notInMessagesId.union(retainSet.union(equals.union(strictlyLessThan))).map(x => (x._1 ,(x._2, x._3))).toMap

            new ShortestPaths(updates)
        }

        def sendMessage(triplet: EdgeTriplet[ShortestPaths, Char]): Iterator[(VertexId, ForwardMessages)] ={
            val forwardMessages = triplet.srcAttr.paths
                .filter(
                    start =>
                        start._2._2 + 1.0 <  (if( triplet.dstAttr.paths.contains(start._1)) triplet.dstAttr.paths(start._1)._2 else Double.PositiveInfinity)
                )
                .map(x => new ForwardMessage(x._1, Set(triplet.srcId), x._2._2 + 1.0)).toSet

            if(forwardMessages.nonEmpty){
                Iterator((triplet.dstId, new ForwardMessages(forwardMessages)))
            }else{
                Iterator.empty
            }
        }

        def mergeMessages(forwardMessages1: ForwardMessages, forwardMessages2: ForwardMessages): ForwardMessages ={

            val messages1 = forwardMessages1.messages.map(x => (x.start, (x.from, x.cost))).toMap
            val messages2 = forwardMessages2.messages.map(x => (x.start, (x.from, x.cost))).toMap
            val intersect = messages1.keySet.intersect(messages2.keySet)
            val extersect1 = messages1.filter(x => !intersect.contains(x._1)).map(x => new ForwardMessage(x._1, x._2._1, x._2._2)).toSet
            val extersect2 = messages2.filter(x => !intersect.contains(x._1)).map(x => new ForwardMessage(x._1, x._2._1, x._2._2)).toSet
            val intersectMessages = intersect.map(
                x =>
                    if(messages1(x)._2 < messages2(x)._2)
                        new ForwardMessage(x, messages1(x)._1, messages1(x)._2)
                    else if(messages1(x)._2 == messages2(x)._2)
                        new ForwardMessage(x, messages2(x)._1 ++ messages1(x)._1, messages2(x)._2)
                    else
                        new ForwardMessage(x, messages2(x)._1, messages2(x)._2)
            )

            val returned = new ForwardMessages(extersect1 ++ intersectMessages ++ extersect2)
            returned
        }

        val shortesPathsFilled = initialGraph.pregel(initialMsg = new ForwardMessages(Set()), maxIterations = maxIter, activeDirection = EdgeDirection.Either)(
            vertexUpdate,
            sendMessage,
            mergeMessages
        )

        shortesPathsFilled
    }
}

object MainObj {
    def main(args: Array[String]): Unit = {
        val conf = new SparkConf().setAppName("Test APSP").setMaster("local[*]")
        val sc = new SparkContext(conf)
        sc.setLogLevel("OFF")
        val lines = Source.fromFile(args{0}).getLines()
        val read = GraphXIntro.readRatingsFile(lines, sc)
        val graph = read._3

        val start = Calendar.getInstance().getTimeInMillis
        val apsp = new APSP()
        val shortestPaths = apsp.unweightedFlyodWarshall(graph, read._1.length)
        shortestPaths.vertices.collect().map(x => (x._1, x._2.paths.filter(y => y._2._2 < Double.PositiveInfinity))).foreach(println)
        val end = Calendar.getInstance().getTimeInMillis
        val difference = Calendar.getInstance()
        difference.setTimeInMillis(end - start)
        println("The total execution time taken is "+difference.getTime.getTime/1000.0+" secs.")
    }
}

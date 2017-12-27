# A note on implementing community detection using Apache Spark + GraphX

## Girvan Newman Algorithm

This is one of the earliest methods of community detection. This method is simple to understand and can be easily distributed across clusters for faster processing. Key assumption is that the graph is undirected and unweighted. But it is not hard to extend to directed graphs + weighted edges.

The algorithm is fairly straightforward. It defines a new measure called `edge betweenness centrality` based on which a divisive hierarchical clustering algorithm is designed to find communities. The stopping criteria for this uses a popular metric called `modularity` which quantifies how cohesive the communities are during the clustering process.

>Side note: A bit of search reveled no implementation of this algorithm in a distributed way (mainly because its slow and better algorithms are available?). So, this note would pave way to use this naive algorithm inspite of its high time complexity.

### Edge Betweenness Centrality
Given a graph G(V,E), Edge betweenness centrality is the total number of shortest paths from any node u to any other node v. This is calculated by running breadth first search on a graph starting from each vertex u in V. Thus we will be running BFS O(V) time to find the number of shortest paths that pass through every edge in the graph.

More details about the algorithm can be found by reading the original paper from Grivan and Newman which can be found [here](https://arxiv.org/abs/cond-mat/0308217).

The key thing to notice is the repetition of the process of BFS and the calculation of `Betweenness` for a particular BFS run can be parallelized. We can do parallelization in two ways and I will discuss shortly how each one is advantageous.



### Run BFS for each node in Parallel

This is a obvious way to parallelize. Create an RDD for the vertices of the graph.

```
vertices = sc.parallelize(graph.vertices)
bfs = vertices.map(vertex => (vertex, BFS(vertex, graph)))
betweenness = bfs.map(vertex => calculateBetweenness(vertex, graph))
```

A keen observer would have noticed the fact that we are not really parallelizing the graph. The entire graph is stored in the main memory of the node where the driver program is running. This works very efficiently for small graphs but will fail if the graph cannot be stored in the main memory. This is the case for many of the available BIG DATA graphs like IMDB or Co-Authorship networks.

### GraphX and Pregel API

Google came up with a nice way to process graph data using the idea of flow networks. Pregel is a map-reduce kind of framework that has processes graph stored in a distributed way.

>Side Note: GraphX uses two RDD to store data about a graph. `VertexRDD` and `EdgeRDD`. Both these classes are abstract and accepts the type of data that a vertex and edge stores. In Scala, both vertex and edge data should be immutable, (so, no `mutable.Map()` or `mutable.Set()`).

Now, the idea is to compute the number of shortest path from a given source. It is easily found by sum of shortest paths for all the neighbors. This is basically computing a version of `All-pair shortest paths` algorithm. Any given node need not know about the entire graph to calculate the shortest path and there lies our parallelism.

Here is how Pregel works (In very basic terms, not using `Superstep` notation.
1. Messages are passed from one node to another, using a custom `Message` class. A vertex is activated for this iteration if it receives messages.
1. Every vertex `aggregates` the message and processes them updating its values (Spark has immutable objects, we need to create new instance of everything a node is holding). This is done using a `VertexProgram`. A `VertexProgram` is run every time a vertex receives messages.
1. Every vertex receives messages from its neighbors. The messages are aggregated into a single message using an `AggregatorProgram`. So when a vertex receives messages, they are aggregated using the `AggregatorProgram` and then sent to the `VertexProgram`.
1. The vertex processes the message, creates a new value and sends out a new `Message` to all its neighbors.

This is repeated till we have no messages sent from any vertex (no vertex is activated)

```

//Data Structures - Vertex
//Maintain a map of source -> how many shortest paths
//and update them as and when you receive messages.

class ShortestPaths(val paths: Map[VertexId, (Set[VertexId], Double)]) extends Serializable

//Data Structures - Message
//Forward your current estimate of number of shortest paths to your neighbours
class Message(val start: VertexId, val from: Set[VertexId], val cost: Double) extends Serializable

//Data Structures - Collection of messages
class ForwardMessages(val messages: Set[Message]) extends Serializable

// GraphX syntax - Calling our message flow.
// Set an initial message to kick off the flow.
// For each self, set shortest path to itself as 1.
graph.pregel(initialMsg = new ForwardMessages(Set()), maxIterations = maxIter, activeDirection = EdgeDirection.Either)(
            vertexUpdate,
            sendMessage,
            mergeMessages
        )
```

The `vertexUpdate()` receives `ForwardMessages` and creates a new `ShortestPaths` object and returns it. It retains the old values and updates only those that are passed in the `ForwardMessages` object.

The `sendMessage()` creates new set of messages for all update values (sources for which the number of shortest paths for updated).

The `mergeMessages()` adds up all `Message` objects and creates a new `ForwardMessages`.

This method will converge eventually, since the graph is undirected and unweighted. the number of messages that needs to be passed is equal to the diameter of the graph. The beauty of this approach is that this works well for disconnected graphs.

At every step in Girvan-Newman algorithm, we need to recompute betweenness centrality after removal of an edge. This can be done efficiently having disconnected components, without worrying if we need to find the connected components and run a BFS separately.

The main drawback of this approach is that this sends huge number of messages across clusters due to the distributed nature of the graph. It requires processors and nodes with high computing power to really scale the algorithm for better use.

### Remarks
We can use these approach to parallelize the computation of BFS which is a crucial step of the algorithm. A completely distributed graph requires lots of message interactions between the cluster nodes and driver nodes, which calls for high performance machines. A graph which can be put in the main memory can use the first approach to avoid sending lots of messages. It is up to the reader to use one of the two approaches (or mention a new one in the comments) based on their needs. I will update the code in GitHub soon.

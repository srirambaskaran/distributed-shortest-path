# Distributed Shortest Path Algorithm

This is one of the classical problems in graph theory. Many algorithms have been defined to solve this problem.

## Repeating shortest path repeatedly

1. Running BFS algorithm repeatedly from each vertex: this is a leveraging the unweighted graph property running a simple traversal algorithm repeatedly. If it
1. Running Djikstra's algorithm from each vertex

## Flyod-Warshall Algorithm

Description of the algorithm is given in [wikipedia](https://en.wikipedia.org/wiki/Floyd%E2%80%93Warshall_algorithm).

The algorithm is an efficient way to compute the all-pair shortest paths using message passing technique.

The given implementation uses similar concept using pregal API in graphX to pass messages across different vertices. 

A more detailed note is given [here](https://gist.github.com/srirambaskaran/573927ee01f3673d3a9182bacbc9ed39). Source code is given at `src/`.

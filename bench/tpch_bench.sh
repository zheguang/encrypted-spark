#!/bin/bash

set -e

dex_variant=$1
reps=${2:-1}

if [ -z $dex_variant ]; then
	  echo "Usage: tpch_bench.sh <dex_variant> <reps>"
	  exit 123
fi

echo "dex variant=$1"
echo "reps=$2"

jar_jdbc=third_party/postgresql-42.0.0.jar
jar_bc=third_party/bcprov-jdk15on-164.jar

for i in $(eval echo {1..$reps}); do
	  echo "rep=$i"
	  ./bin/spark-submit --master local[*] \
			                 --jars $jar_jdbc,$jar_bc \
			                 --driver-class-path $jar_jdbc:$jar_bc  \
			                 --conf spark.executor.extraClassPath=$jar_jdbc:$jar_bc \
			                 --driver-memory 6g   \
			                 --conf spark.driver.maxResultSize=0 \
			                 --class org.apache.spark.examples.sql.dex.TPCHBench ./examples/target/scala-2.11/jars/spark-examples_2.11-2.4.0.jar \
			                 $dex_variant \
			  &> /data/log-$dex_variant-$i
done

echo "done"

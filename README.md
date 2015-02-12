# KaBoom - A High Performance Consumer Client for Kafka
KaBoom uses Krackle to consume from partitions of topics in Kafka and write them to boom files in HDFS.  

## Features
* Uses the [Curator Framework](http://curator.apache.org/) for  [Apache Zookeeper](zookeeper.apache.org) to distribute work amongst multiple servers
* Supports writing to secured Hadoop clusters via Kerberos based secure impersonation (conveniently pulled from [Flume](http://flume.apache.org/))
* Recovers from Kafka server failures (even when newly elected leaders weren't in-sync when elected)
* Supports consuming with either GZIP or Snappy compression
* Configurable: Each topic can be configured with a unique HDFS path template with date/time variable substitution
* Supports flagging timestamp template HDFS directories as 'Ready' when all a topic's partition's messages have been written for a given hour

## Author(s)
* Will Chartrand (original author)
* [Dave Ariens](<mailto:dariens@blackberry.com>) (current maintainer)

## Building
Performing a Maven install produces a RPM package that currently installs on Cent OS based Linux distributions..

## Configuring
Below is an example configuration for running a KaBoom instance that consumes messages in two topics (topic1, topic2) and writes them to HDFS paths owned by different HDFS users.

### Note: Kaboom version 0.7.1 

0.7.1 Introduces a few new required configuration properties and changes how HDFS output paths and file systems are defined.  It aso supports writing to open files intended to be read and consumed by downstream tooling and map reduce jobs.  A configurable flush interval has been exposed to periodically perform an HDFS flush on the open file.

New required configuration property: 

```
# Define the URI to your Hadoop file sysetm (this was previously required to be included before each topic's path)
hadooop.fs.uri=hdfs://hadoop.site.cluster-01
```

For writing to open files turn this (new property) off and _tmp_<fileName> directories will not be created to hold the open files:

```
# Store open files in a temp directory (based off filename) while they are open
kaboom.useTempOpenFileDirectory=false
```

These two properties are still recommended:

```
# If the expected offset is greater than than actual offset and also higher than the high watermark 
# then perhaps the broker we're receiving messages from has changed and the new broker has a 
# lower offset because it was behind when it took over...  If so, allow ourselves to sink to the new high watermark  
kaboom.sinkToHighWatermark=true

# Kaboom stores the offsets for the topic-partition it's assigned in ZK...  Sometimes there's a need to override that, 
# So if a znode is created alongside the offset znode called offset_override, kaboom will start there instead
kaboom.allowOffsetOverrides=true
```

Topics and their output paths are now configured a little differently.  An hdfsRootDir is now required for every topic in addition to the proxy user.  

Note that the hdfsRootDir is prefixed with the hadoop.fs.uri before it's used.

```
topic.devtest-test1.hdfsRootDir=/service/82/devtest/logs/%y%M%d/%H/test1
topic.devtest-test1.proxy.user=dariens
```

Multiple numbered HDFS output directories are supported.  The numbers are meaningless and the duration determines how long the file will remain open before it's closed off.  

Note: The duration + 60 seconds is used.  The 60 seconds is an attempt at ensuring that late events don't still require the open file.

```
topic.devtest-test1.hdfsDir.1=data
topic.devtest-test1.hdfsDir.1.duration=3600
```

The hdfsDir above is prefixed with the hdfsRootDir for the topic (which in turn is prefixed with the hadoop.fs.uri).  In this example the fully populated URL would be:

hdfs://hadoop.site.cluster-01//service/82/devtest/logs/%y%M%d/%H/test1/data/

The file created would be a boom  file called: <partition_number>_<offset>.bm, example:

hdfs://hadoop.site.cluster-01/service/82/devtest/logs/%y%M%d/%H/test1/data/0_12345678.bm

### Example Configuration FIle /opt/klogger/config/klogger.properties (defines Klogger configuration, topics, and ports)
```
# This must be unique amongst all KaBoom instances
kaboom.id=666

# Hadoop URI
hadooop.fs.uri=hdfs://hadoop.site.cluster-01

# How often to periodically flush open output paths to HDFS
kaboom.boomWriter.periodicHdfsFlushInterval=30000

# Store open files in a temp directory (based off filename) while they are open
kaboom.useTempOpenFileDirectory = false

kerberos.principal = flume@AD0.BBLABS
kerberos.keytab = /opt/kaboom/config/kaboom.keytab
kaboom.readyflag.prevhours = 30

zookeeper.connection.string=kaboom1.site.dc1:2181,kaboom2.site.dc1:2181,kaboom3.site.dc1:2181/KaBoom

kafka.zookeeper.connection.string=kafka1.site.dc1:2181,kafka2.site.dc1:2181,kafka3.site.dc1:2181
fetch.wait.max.ms=5000
auto.offset.reset=smallest
socket.receive.buffer.bytes=1048576
fetch.message.max.bytes=10485760
kaboom.sinkToHighWatermark=true
kaboom.allowOffsetOverrides=true

metadata.broker.list=kafka1.site.dc1:9092,kafka2.site.dc1:9092,kafka3.site.dc1:9092

topic.topic1.hdfsRootDir=/service/82/topic1/logs/%y%M%d/%H/topic1
topic.topic1.proxy.user=someuser
topic.topic1.hdfsDir.1=data
topic.topic1.hdfsDir.1.duration=3600
```

### Example Configuration FIle: /opt/kaboom/config/kaboom-env.sh (defines runtime configuration and JVM properties)
```
JAVA=`which java`
BASEDIR=/opt/kaboom
BINDIR="$BASEDIR/bin"
LIBDIR="$BASEDIR/lib"
LOGDIR="/var/log/kaboom"
CONFIGDIR="$BASEDIR/config"
JMXPORT=9580
LOG4JPROPERTIES=$CONFIGDIR/log4j.properties
PIDBASE=/var/run/kaboom
KABOOM_USER=kafka

JAVA_OPTS=""
JAVA_OPTS="$JAVA_OPTS -server"
JAVA_OPTS="$JAVA_OPTS -Xms6G -Xmx6G"
JAVA_OPTS="$JAVA_OPTS -XX:+UseParNewGC -XX:+UseConcMarkSweepGC"
JAVA_OPTS="$JAVA_OPTS -XX:+UseCMSInitiatingOccupancyOnly -XX:+CMSConcurrentMTEnabled -XX:+CMSScavengeBeforeRemark"
JAVA_OPTS="$JAVA_OPTS -XX:CMSInitiatingOccupancyFraction=30"

JAVA_OPTS="$JAVA_OPTS -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintTenuringDistribution"
JAVA_OPTS="$JAVA_OPTS -Xloggc:$LOGDIR/gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=10M"

JAVA_OPTS="$JAVA_OPTS -Djava.awt.headless=true"
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote"
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.ssl=false"
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.port=$JMXPORT"

JAVA_OPTS="$JAVA_OPTS -Dlog4j.configuration=file:$LOG4JPROPERTIES"

JAVA_OPTS="$JAVA_OPTS -Dkaboom.logs.dir=$LOGDIR"

CLASSPATH=$CONFIGDIR:/etc/hadoop/conf:$LIBDIR/*
```

### Example Configuration FIle: /opt/kaboom/config/log4j.properties (logging)
```
kaboom.logs.dir=/var/log/kaboom
log4j.rootLogger=INFO, kaboomAppender

log4j.appender.stdout=org.apache.log4j.ConsoleAppender
log4j.appender.stdout.layout=org.apache.log4j.PatternLayout
log4j.appender.stdout.layout.ConversionPattern=[%d] %p %m (%c)n

log4j.appender.kaboomAppender=org.apache.log4j.DailyRollingFileAppender
log4j.appender.kaboomAppender.DatePattern='.'yyy-MM-dd-HH
log4j.appender.kaboomAppender.File=${kaboom.logs.dir}/server.log
log4j.appender.kaboomAppender.layout=org.apache.log4j.PatternLayout
log4j.appender.kaboomAppender.layout.ConversionPattern=[%d] %p %m (%c)%n
```

## Running
After configuration simply start the kaboom service 'service kabom start'.

## Monitoring
Exposed via [Coda Hale's Metric's](https://github.com/dropwizard/metrics) are metrics for monitoring message count, size, and lag (measure of how far behind KaBoom is compared to most recent message in Kafka--both in offset count and seconds):

Kaboom (Aggregate metrics--for the KaBoom cluster):

* Gauge: max message lab sec 
* Gauge: sum message lag sec 
* Gauge: avg message lag sec 
* Gauge: max message lag 
* Gauge: sum message lag
* Gauge: avg message lag 
* Gauge: avg messages written per sec
* Gauge: total messages written per sec

Kaboom (Instance metrics -- for a KaBoom worker assigned to a topic and partition):

* Gauge: offset lag
* Gauge: seconds lag
* Gauge: messages written per second
* Gauge: early offsets received (when compression is enabled and messages are included from earlier than requested offset)
* Meter: boom writes

Krackle:

* Meter: MessageRequests
* Meter: MessageRequestsTotal
* Meter: MessagesReturned
* Meter: MessagesReturnedTotal
* Meter: BytesReturned
* Meter: BytesReturnedTotal
* Meter: MessageRequestsNoData
* Meter: MessageRequestsNoDataTotal
* Meter: BrokerReadAttempts
* Meter: BrokerReadAttemptsTotal
* Meter: BrokerReadSuccess
* Meter: BrokerReadSuccessTotal
* Meter: BrokerReadFailure
* Meter: BrokerReadFailureTotal

## Boom Files

This section contains portions from the [hadoop-logdriver](https://github.com/blackberry/hadoop-logdriver) project's description of Boom files.

A Boom file is a place where we store logs in HDFS.

The goals of Boom are:

* Be splittable by Hadoop, so that we can efficiently run MapReduce jobs against it.
* Be compressed to save storage.
* Be able to determine order of lines, even if they are processed out of order.

## File extention
The .bm file extension is used for Boom files.

## Boom File Format
A Boom file is a specific type of Avro [Object Container File](http://avro.apache.org/docs/1.6.3/spec.html#Object+Container+Files).  Familiarize yourself with those docs before you keep going.

Specifically, we always use a compression codec of 'deflate' and we always use the following Schema:

    {
      "type": "record",
      "name": "logBlock",
      "fields": [
        { "name": "second",      "type": "long" },
        { "name": "createTime",  "type": "long" },
        { "name": "blockNumber", "type": "long" },
        { "name": "logLines", "type": {
          "type": "array",
            "items": {
              "type": "record",
              "name": "messageWithMillis",
              "fields": [ 
                { "name": "ms",      "type": "long" },
                { "name": "eventId", "type": "int", "default": 0 },
                { "name": "message", "type": "string" }
              ]
            }
        }}
      ]
    }

### Basic Structure
The file contains any number of "logBlock" records.  Each logBlock contains data for multiple log lines, but all of the log lines in the record are timestamped in the same second.  Log lines in the same logBlock can have difference millisecond timestamps.

### Fields in logBlock
* second : the number of seconds since Jan 1, 1970 UTC.  All log lines in this record are timestamped with a time that occurs within this second.
* createTime : the time (in milliseconds) that this logBlock was created.  This is used for sorting logBlocks.
* blockNumber : a number indicating the sequence in which the logBlocks were written by whatever wrote the file.  This is used for sorting logBlocks.
* logLines : an array of "messageWithMillis" records, one per log line.

### Fields in messageWithMillis
* ms : the milliseconds part of the timestamp for this log line.  To get the complete timestamp, use second * 1000 + ms.
* eventId : an event identifier, reserved for future use.  Use 0 for raw log lines.
* message : the contents of the log line, excluding the timestamp and one space after the timestamp.

## Boom suggested defaults
Although no limitations should be assumed on the file beyond what has already been stated, these are sensible defaults that should be followed.

* The logLines field should contain no more that 1000 messageWithMillis entries.  If there are more than 1000 log lines within a second, then use multiple logBlock's with the same second value.
* The Avro Object Container File defines a "sync interval".  A good value for this seems to be 2MB (2147483648).
* While we are required to use the deflate codec, the compression level is configurable.  If you don't have a specific need, then level 6 is a good default.

## Sorting log lines
If the order of log lines is important, then the fields can be sorted by comparing fields in this order

* timestamp : first timestamp is first (after adding seconds and milliseconds)
* createTime : logBlocks that were written first go first.
* blockNumber : If two logBlocks were written in the same millisecond, then use them in the order they were written.
* index within logLines : If the log lines are the same timestamp, written in the same block, then the order is determined by where they are within the logLines array.

This is the default sorting for LogLineData objects.

## Contributing
To contribute code to this repository you must be [signed up as an official contributor](http://blackberry.github.com/howToContribute.html).

## Disclaimer
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

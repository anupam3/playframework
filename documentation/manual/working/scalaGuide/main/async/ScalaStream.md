<!--- Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com> -->
# Streaming HTTP responses

## Standard responses and `Content-Length` header

Since HTTP 1.1, to keep a single connection open to serve several HTTP requests and responses, the server must send the appropriate `Content-Length` HTTP header along with the response. 

By default, you are not specifying a `Content-Length` header when you send back a simple result, such as:

```scala
def index = Action {
  Ok("Hello World")
}
```

Of course, because the content you are sending is well-known, Play is able to compute the content size for you and to generate the appropriate header.

> **Note**: for text-based content it is not as simple as it looks, since the `Content-Length` header must be computed according the character encoding used to translate characters to bytes.

Actually, we previously saw that the response body is specified using a `play.api.http.HttpEntity`:

```scala
def index = Action {
   Result(
    header = ResponseHeader(200, Map.empty),
    body = HttpEntity.Strict(ByteString("Hello world!"), Some("text/plain"))
  )
}
```

This means that to compute the `Content-Length` header properly, Play must consume the whole content and load it into memory. 

## Sending large amounts of data

If it’s not a problem to load the whole content into memory, what about large data sets? Let’s say we want to return a large file to the web client.

Let’s first see how to create an `Source[ByteString, _]` for the file content:

```scala
val file = new java.io.File("/tmp/fileToServe.pdf")
val path: java.nio.file.Path = file.toPath
val source: Source[ByteString, _] = FileIO.fromPath(path)
```

Now it looks simple right? Let’s just use this streamed HttpEntity to specify the response body:

```scala
def index = Action {

  val file = new java.io.File("/tmp/fileToServe.pdf")
  val path: java.nio.file.Path = file.toPath
  val source: Source[ByteString, _] = FileIO.fromPath(path)
    
  Result(
    header = ResponseHeader(200, Map.empty),
    body = HttpEntity.Streamed(source, None, Some("application/pdf"))
  )
}
```

Actually we have a problem here. As we don’t specify the `Content-Length` in streamed entity, Play will have to compute it itself, and the only way to do this is to consume the whole source content and load it into memory, and then compute the response size.

That’s a problem for large files that we don’t want to load completely into memory. So to avoid that, we just have to specify the `Content-Length` header ourselves.

```scala
def index = Action {

  val file = new java.io.File("/tmp/fileToServe.pdf")
  val path: java.nio.file.Path = file.toPath
  val source: Source[ByteString, _] = FileIO.fromPath(path)
    
  Result(
    header = ResponseHeader(200, Map.empty),
    body = HttpEntity.Streamed(source, Some(file.length), Some("application/pdf"))
  )
}
```

This way Play will consume the body source in a lazy way, copying each chunk of data to the HTTP response as soon as it is available.

## Serving files

Of course, Play provides easy-to-use helpers for common task of serving a local file:

```scala
def index = Action {
  Ok.sendFile(new java.io.File("/tmp/fileToServe.pdf"))
}
```

This helper will also compute the `Content-Type` header from the file name, and add the `Content-Disposition` header to specify how the web browser should handle this response. The default is to ask the web browser to download this file by adding the header `Content-Disposition: attachment; filename=fileToServe.pdf` to the HTTP response.

You can also provide your own file name:

```scala
def index = Action {
  Ok.sendFile(
    content = new java.io.File("/tmp/fileToServe.pdf"),
    fileName = _ => "termsOfService.pdf"
  )
}
```

If you want to serve this file `inline`:

```scala
def index = Action {
  Ok.sendFile(
    content = new java.io.File("/tmp/fileToServe.pdf"),
    inline = true
  )
}
```

Now you don't have to specify a file name since the web browser will not try to download it, but will just display the file content in the web browser window. This is useful for content types supported natively by the web browser, such as text, HTML or images.

## Chunked responses

For now, it works well with streaming file content since we are able to compute the content length before streaming it. But what about dynamically computed content, with no content size available?

For this kind of response we have to use **Chunked transfer encoding**. 

> **Chunked transfer encoding** is a data transfer mechanism in version 1.1 of the Hypertext Transfer Protocol (HTTP) in which a web server serves content in a series of chunks. It uses the `Transfer-Encoding` HTTP response header instead of the `Content-Length` header, which the protocol would otherwise require. Because the `Content-Length` header is not used, the server does not need to know the length of the content before it starts transmitting a response to the client (usually a web browser). Web servers can begin transmitting responses with dynamically-generated content before knowing the total size of that content.
> 
> The size of each chunk is sent right before the chunk itself, so that a client can tell when it has finished receiving data for that chunk. Data transfer is terminated by a final chunk of length zero.
>
> <https://en.wikipedia.org/wiki/Chunked_transfer_encoding>

The advantage is that we can serve the data **live**, meaning that we send chunks of data as soon as they are available. The drawback is that since the web browser doesn’t know the content size, it is not able to display a proper download progress bar.

Let’s say that we have a service somewhere that provides a dynamic `InputStream` computing some data. First we have to create an `Source` for this stream:

```scala
val data = getDataStream
val dataContent: Source[ByteString, _] = StreamConverters.fromInputStream(data)
```

We can now stream these data using a `Ok.chunked`:

```scala
def index = Action {
  val CHUNK_SIZE = 100
  val data = getDataStream
  val dataContent: Source[ByteString, _] = StreamConverters.fromInputStream(data, CHUNK_SIZE)
  
  Ok.chunked(dataContent)
}
```

Of course, we can use any `Source` to specify the chunked data:

```scala
def index = Action {
  val source = Source.apply(List("kiki", "foo", "bar"))
  Ok.chunked(source)
}
```

We can inspect the HTTP response sent by the server:

```
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Transfer-Encoding: chunked

4
kiki
3
foo
3
bar
0

```

We get three chunks followed by one final empty chunk that closes the response.

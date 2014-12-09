---
layout: global
title: Feature Extraction and Transformation - MLlib
displayTitle: <a href="mllib-guide.html">MLlib</a> - Feature Extraction and Transformation
---

* Table of contents
{:toc}


## TF-IDF

[Term frequency-inverse document frequency (TF-IDF)](http://en.wikipedia.org/wiki/Tf%E2%80%93idf) is a feature 
vectorization method widely used in text mining to reflect the importance of a term to a document in the corpus.
Denote a term by `$t$`, a document by `$d$`, and the corpus by `$D$`.
Term frequency `$TF(t, d)$` is the number of times that term `$t$` appears in document `$d$`,
while document frequency `$DF(t, D)$` is the number of documents that contains term `$t$`.
If we only use term frequency to measure the importance, it is very easy to over-emphasize terms that
appear very often but carry little information about the document, e.g., "a", "the", and "of".
If a term appears very often across the corpus, it means it doesn't carry special information about
a particular document.
Inverse document frequency is a numerical measure of how much information a term provides:
`\[
IDF(t, D) = \log \frac{|D| + 1}{DF(t, D) + 1},
\]`
where `$|D|$` is the total number of documents in the corpus.
Since logarithm is used, if a term appears in all documents, its IDF value becomes 0.
Note that a smoothing term is applied to avoid dividing by zero for terms outside the corpus.
The TF-IDF measure is simply the product of TF and IDF:
`\[
TFIDF(t, d, D) = TF(t, d) \cdot IDF(t, D).
\]`
There are several variants on the definition of term frequency and document frequency.
In MLlib, we separate TF and IDF to make them flexible.

Our implementation of term frequency utilizes the
[hashing trick](http://en.wikipedia.org/wiki/Feature_hashing).
A raw feature is mapped into an index (term) by applying a hash function.
Then term frequencies are calculated based on the mapped indices.
This approach avoids the need to compute a global term-to-index map,
which can be expensive for a large corpus, but it suffers from potential hash collisions,
where different raw features may become the same term after hashing.
To reduce the chance of collision, we can increase the target feature dimension, i.e., 
the number of buckets of the hash table.
The default feature dimension is `$2^{20} = 1,048,576$`.

**Note:** MLlib doesn't provide tools for text segmentation.
We refer users to the [Stanford NLP Group](http://nlp.stanford.edu/) and 
[scalanlp/chalk](https://github.com/scalanlp/chalk).

<div class="codetabs">
<div data-lang="scala" markdown="1">

TF and IDF are implemented in [HashingTF](api/scala/index.html#org.apache.spark.mllib.feature.HashingTF)
and [IDF](api/scala/index.html#org.apache.spark.mllib.feature.IDF).
`HashingTF` takes an `RDD[Iterable[_]]` as the input.
Each record could be an iterable of strings or other types.

{% highlight scala %}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.mllib.feature.HashingTF
import org.apache.spark.mllib.linalg.Vector

val sc: SparkContext = ...

// Load documents (one per line).
val documents: RDD[Seq[String]] = sc.textFile("...").map(_.split(" ").toSeq)

val hashingTF = new HashingTF()
val tf: RDD[Vector] = hasingTF.transform(documents)
{% endhighlight %}

While applying `HashingTF` only needs a single pass to the data, applying `IDF` needs two passes: 
first to compute the IDF vector and second to scale the term frequencies by IDF.

{% highlight scala %}
import org.apache.spark.mllib.feature.IDF

// ... continue from the previous example
tf.cache()
val idf = new IDF().fit(tf)
val tfidf: RDD[Vector] = idf.transform(tf)
{% endhighlight %}
</div>
</div>

## Word2Vec 

[Word2Vec](https://code.google.com/p/word2vec/) computes distributed vector representation of words.
The main advantage of the distributed
representations is that similar words are close in the vector space, which makes generalization to 
novel patterns easier and model estimation more robust. Distributed vector representation is 
showed to be useful in many natural language processing applications such as named entity 
recognition, disambiguation, parsing, tagging and machine translation.

### Model

In our implementation of Word2Vec, we used skip-gram model. The training objective of skip-gram is
to learn word vector representations that are good at predicting its context in the same sentence. 
Mathematically, given a sequence of training words `$w_1, w_2, \dots, w_T$`, the objective of the
skip-gram model is to maximize the average log-likelihood 
`\[
\frac{1}{T} \sum_{t = 1}^{T}\sum_{j=-k}^{j=k} \log p(w_{t+j} | w_t)
\]`
where $k$ is the size of the training window.  

In the skip-gram model, every word $w$ is associated with two vectors $u_w$ and $v_w$ which are 
vector representations of $w$ as word and context respectively. The probability of correctly 
predicting word $w_i$ given word $w_j$ is determined by the softmax model, which is 
`\[
p(w_i | w_j ) = \frac{\exp(u_{w_i}^{\top}v_{w_j})}{\sum_{l=1}^{V} \exp(u_l^{\top}v_{w_j})}
\]`
where $V$ is the vocabulary size. 

The skip-gram model with softmax is expensive because the cost of computing $\log p(w_i | w_j)$ 
is proportional to $V$, which can be easily in order of millions. To speed up training of Word2Vec, 
we used hierarchical softmax, which reduced the complexity of computing of $\log p(w_i | w_j)$ to
$O(\log(V))$

### Example 

The example below demonstrates how to load a text file, parse it as an RDD of `Seq[String]`,
construct a `Word2Vec` instance and then fit a `Word2VecModel` with the input data. Finally,
we display the top 40 synonyms of the specified word. To run the example, first download
the [text8](http://mattmahoney.net/dc/text8.zip) data and extract it to your preferred directory.
Here we assume the extracted file is `text8` and in same directory as you run the spark shell.  

<div class="codetabs">
<div data-lang="scala">
{% highlight scala %}
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.feature.Word2Vec

val input = sc.textFile("text8").map(line => line.split(" ").toSeq)

val word2vec = new Word2Vec()

val model = word2vec.fit(input)

val synonyms = model.findSynonyms("china", 40)

for((synonym, cosineSimilarity) <- synonyms) {
  println(s"$synonym $cosineSimilarity")
}
{% endhighlight %}
</div>
</div>

## StandardScaler

Standardizes features by scaling to unit variance and/or removing the mean using column summary
statistics on the samples in the training set. This is a very common pre-processing step.

For example, RBF kernel of Support Vector Machines or the L1 and L2 regularized linear models
typically work better when all features have unit variance and/or zero mean.

Standardization can improve the convergence rate during the optimization process, and also prevents
against features with very large variances exerting an overly large influence during model training.

### Model Fitting

[`StandardScaler`](api/scala/index.html#org.apache.spark.mllib.feature.StandardScaler) has the
following parameters in the constructor:

* `withMean` False by default. Centers the data with mean before scaling. It will build a dense
output, so this does not work on sparse input and will raise an exception.
* `withStd` True by default. Scales the data to unit variance.

We provide a [`fit`](api/scala/index.html#org.apache.spark.mllib.feature.StandardScaler) method in
`StandardScaler` which can take an input of `RDD[Vector]`, learn the summary statistics, and then
return a model which can transform the input dataset into unit variance and/or zero mean features
depending how we configure the `StandardScaler`.

This model implements [`VectorTransformer`](api/scala/index.html#org.apache.spark.mllib.feature.VectorTransformer)
which can apply the standardization on a `Vector` to produce a transformed `Vector` or on
an `RDD[Vector]` to produce a transformed `RDD[Vector]`.

Note that if the variance of a feature is zero, it will return default `0.0` value in the `Vector`
for that feature.

### Example

The example below demonstrates how to load a dataset in libsvm format, and standardize the features
so that the new features have unit variance and/or zero mean.

<div class="codetabs">
<div data-lang="scala">
{% highlight scala %}
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.feature.StandardScaler
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.util.MLUtils

val data = MLUtils.loadLibSVMFile(sc, "data/mllib/sample_libsvm_data.txt")

val scaler1 = new StandardScaler().fit(data.map(x => x.features))
val scaler2 = new StandardScaler(withMean = true, withStd = true).fit(data.map(x => x.features))

// data1 will be unit variance.
val data1 = data.map(x => (x.label, scaler1.transform(x.features)))

// Without converting the features into dense vectors, transformation with zero mean will raise
// exception on sparse vector.
// data2 will be unit variance and zero mean.
val data2 = data.map(x => (x.label, scaler2.transform(Vectors.dense(x.features.toArray))))
{% endhighlight %}
</div>
</div>

## Normalizer

Normalizer scales individual samples to have unit $L^p$ norm. This is a common operation for text
classification or clustering. For example, the dot product of two $L^2$ normalized TF-IDF vectors
is the cosine similarity of the vectors.

[`Normalizer`](api/scala/index.html#org.apache.spark.mllib.feature.Normalizer) has the following
parameter in the constructor:

* `p` Normalization in $L^p$ space, $p = 2$ by default.

`Normalizer` implements [`VectorTransformer`](api/scala/index.html#org.apache.spark.mllib.feature.VectorTransformer)
which can apply the normalization on a `Vector` to produce a transformed `Vector` or on
an `RDD[Vector]` to produce a transformed `RDD[Vector]`.

Note that if the norm of the input is zero, it will return the input vector.

### Example

The example below demonstrates how to load a dataset in libsvm format, and normalizes the features
with $L^2$ norm, and $L^\infty$ norm.

<div class="codetabs">
<div data-lang="scala">
{% highlight scala %}
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.feature.Normalizer
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.util.MLUtils

val data = MLUtils.loadLibSVMFile(sc, "data/mllib/sample_libsvm_data.txt")

val normalizer1 = new Normalizer()
val normalizer2 = new Normalizer(p = Double.PositiveInfinity)

// Each sample in data1 will be normalized using $L^2$ norm.
val data1 = data.map(x => (x.label, normalizer1.transform(x.features)))

// Each sample in data2 will be normalized using $L^\infty$ norm.
val data2 = data.map(x => (x.label, normalizer2.transform(x.features)))
{% endhighlight %}
</div>
</div>

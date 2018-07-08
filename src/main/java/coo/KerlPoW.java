package coo;

import jota.IotaLocalPoW;
import jota.pow.ICurl;
import jota.pow.SpongeFactory;
import jota.utils.Converter;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author Andreas C. Osowski
 */
public class KerlPoW implements IotaLocalPoW {

  public final static int NONCE_START_TRIT = 7938;
  public final static int NONCE_LENGTH_TRIT = 81;
  public final static int NONCE_START_TRYTE = NONCE_START_TRIT / 3;
  public final static int NONCE_LENGTH_TRYTE = NONCE_LENGTH_TRIT / 3;

  private final Logger log = Logger.getLogger("KerlPoW");
  private KerlPoWSettings settings;

  public KerlPoW() {
    this(new KerlPoWSettings());
  }

  public KerlPoW(KerlPoWSettings settings) {
    this.settings = settings;
    if (settings.numberOfThreads <= 0) {
      int available = Runtime.getRuntime().availableProcessors();
      settings.numberOfThreads = Math.max(1, Math.floorDiv(available * 8, 10));
    }

  }

  @Override
  public String performPoW(String trytes, int minWeightMagnitude) {
    final ExecutorService executorService = Executors.newFixedThreadPool(settings.numberOfThreads);
    final AtomicBoolean resultFound = new AtomicBoolean(false);
    final List<Searcher> searchers = IntStream.range(0, settings.numberOfThreads)
        .mapToObj((idx) -> new Searcher(trytes, resultFound, minWeightMagnitude))
        .collect(Collectors.toList());
    final List<Future<String>> searcherFutures = searchers.stream()
        .map((s) -> executorService.submit(s))
        .collect(Collectors.toList());

    executorService.shutdown();
    try {
      executorService.awaitTermination(10, TimeUnit.MINUTES);

      for (Future<String> f : searcherFutures) {
        if (f.isDone() && f.get() != null) {
          return trytes.substring(0, NONCE_START_TRYTE) + f.get();
        }
      }
    } catch (ExecutionException | InterruptedException e) {
      e.printStackTrace();
      return null;
    }

    return null;
  }

  public static class KerlPoWSettings {
    public int numberOfThreads = 1;
  }

  class Searcher implements Callable<String> {

    private final AtomicBoolean resultFound;
    private final int targetZeros;

    private int[] trits;
    private int[] hashTrits = new int[243];

    public Searcher(String inputTrytes, AtomicBoolean resultFound, int targetZeros) {
      this.resultFound = resultFound;
      this.trits = Converter.trits(inputTrytes);
      this.targetZeros = targetZeros;
    }

    private boolean shouldAbort() {
      return resultFound.get();
    }

    private void increment(int[] trits, int offset, int size) {
      for (int i = offset; i < (offset + size) && ++trits[i] > 1; ++i) {
        trits[i] = -1;
      }
    }

    private int trailingZeros(int[] trits) {
      int count = 0;
      for (int i = trits.length - 1; i >= 0 && trits[i] == 0; i--) {
        count++;
      }

      return count;
    }

    private void search() {
      ICurl sponge = SpongeFactory.create(SpongeFactory.Mode.KERL);
      increment(trits, NONCE_START_TRIT, NONCE_LENGTH_TRIT);

      sponge.absorb(trits);
      sponge.squeeze(hashTrits);
    }

    @Override
    public String call() throws Exception {
      String result = null;
      while (!shouldAbort()) {
        search();

        if (trailingZeros(hashTrits) >= targetZeros) {
          result = Converter.trytes(trits, NONCE_START_TRIT, NONCE_LENGTH_TRIT);
          resultFound.set(true);
          break;
        }
      }

      return result;
    }
  }
}

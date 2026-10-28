package com.apocollis.chunksteward.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.util.Random;

/**
 * Copies a {@link Random} and moves its state from one instance to another, which the JDK has no method for.
 *
 * <p>A feature job runs a decoration on a copy of the generator's {@code Random} taken where the decoration would have
 * started. The server thread then continues from the state the job ended in, so the draws after it (animals) are the
 * ones an inline populate would have made. The state of a {@code Random} is its 48-bit seed; it is read from the
 * serialized form (the JDK's stable, documented fields) and written back with {@code setSeed}, which scrambles with the
 * multiplier, so writing {@code state ^ MULTIPLIER} gives {@code state} back.
 */
public final class FeatureRandom {
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long MASK = (1L << 48) - 1L;

    private FeatureRandom() {
    }

    /** A new {@code Random} in the same state as {@code source}. */
    public static Random copy(Random source) {
        try {
            return (Random) new ObjectInputStream(new ByteArrayInputStream(serialize(source))).readObject();
        } catch (IOException | ClassNotFoundException failed) {
            throw new IllegalStateException("Random copy failed", failed);
        }
    }

    /** The 48-bit internal state of {@code source}. */
    public static long state(Random source) {
        try (ObjectInputStream in = new StateInput(new ByteArrayInputStream(serialize(source)))) {
            return ((State) in.readObject()).seed & MASK;
        } catch (IOException | ClassNotFoundException failed) {
            throw new IllegalStateException("Random state read failed", failed);
        }
    }

    /** True when the Gaussian cache of {@code source} holds a value, which {@link #setState} cannot carry over. */
    public static boolean hasGaussian(Random source) {
        try (ObjectInputStream in = new StateInput(new ByteArrayInputStream(serialize(source)))) {
            return ((State) in.readObject()).haveNextNextGaussian;
        } catch (IOException | ClassNotFoundException failed) {
            throw new IllegalStateException("Random state read failed", failed);
        }
    }

    /** Puts {@code target} in the internal state {@code state} (and clears its Gaussian cache). */
    public static void setState(Random target, long state) {
        target.setSeed((state & MASK) ^ MULTIPLIER);
    }

    /** Puts {@code target} in the state {@code source} is in. */
    public static void transfer(Random source, Random target) {
        setState(target, state(source));
    }

    private static byte[] serialize(Random source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(source);
        }
        return bytes.toByteArray();
    }

    /** Reads the stream of a {@code java.util.Random} into {@link State}. */
    private static final class StateInput extends ObjectInputStream {
        StateInput(ByteArrayInputStream in) throws IOException {
            super(in);
        }

        /** The stream names {@code java.util.Random}; read it with {@link State}, which has the same fields. */
        @Override
        protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
            ObjectStreamClass desc = super.readClassDescriptor();
            if ("java.util.Random".equals(desc.getName())) {
                return ObjectStreamClass.lookup(State.class);
            }
            return desc;
        }
    }

    /** The serialized fields of {@code java.util.Random}, under the same serial version. */
    static final class State implements Serializable {
        private static final long serialVersionUID = 3905348978240129619L;
        private static final ObjectStreamField[] serialPersistentFields = {
                new ObjectStreamField("haveNextNextGaussian", boolean.class),
                new ObjectStreamField("nextNextGaussian", double.class),
                new ObjectStreamField("seed", long.class)
        };

        long seed;
        boolean haveNextNextGaussian;

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            ObjectInputStream.GetField fields = in.readFields();
            seed = fields.get("seed", 0L);
            haveNextNextGaussian = fields.get("haveNextNextGaussian", false);
        }
    }
}

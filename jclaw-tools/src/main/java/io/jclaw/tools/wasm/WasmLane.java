// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.ValueType;
import io.jclaw.contracts.Result;
import io.jclaw.domain.wasm.WasmSpec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a WebAssembly module as a tool lane.
 *
 * <p>This is the one place jclaw executes code it did not compile and cannot read. A container
 * bounds a process; this bounds something smaller and stricter. The module addresses only its own
 * linear memory, capped by the spec. It reaches the outside world only through host functions the
 * operator granted, and a module that imports one it was not granted fails to instantiate rather
 * than failing at the call, so an ungranted capability is not a guard that could be forgotten but
 * a name that does not resolve. And it is metered: every instruction is counted, and the module
 * is stopped when it has had its share, so a loop costs a bounded amount of someone's afternoon.
 *
 * <p>A module is instantiated per call. Reusing an instance would be faster and would let one
 * tool call leave state for the next, which for third-party code is not a trade worth making.
 *
 * <p>The calling convention is deliberately tiny, because every addition to it is another thing a
 * module author can get wrong. A module exports {@code memory}, {@code jclaw_alloc(len) -> ptr},
 * and {@code jclaw_call(ptr, len) -> i64}, where the argument is UTF-8 JSON and the result packs
 * a pointer in the high half and a length in the low half.
 */
public final class WasmLane {

    /** Stopped because it used its instruction budget. Not an error in the module, a limit. */
    static final class OutOfFuel extends RuntimeException {
        OutOfFuel() {
            super("instruction budget exhausted", null, false, false);
        }
    }

    /** What the host lets a module do, when the spec grants it. */
    public interface HostServices {
        /** Appends to the call's output. */
        void log(String message);

        /** Reads a workspace file, or empty when the guard refuses or it does not exist. */
        java.util.Optional<String> readFile(String path);

        /** Fetches a URL through the host's egress guard, or empty when refused. */
        java.util.Optional<String> httpGet(String url);
    }

    private final WasmModule module;
    private final WasmSpec spec;

    private WasmLane(WasmModule module, WasmSpec spec) {
        this.module = module;
        this.spec = spec;
    }

    /** Parses a module. Rejects one the runtime cannot read, before anything is granted. */
    public static Result<WasmLane, String> load(byte[] wasm, WasmSpec spec) {
        Objects.requireNonNull(wasm, "wasm");
        Objects.requireNonNull(spec, "spec");
        try {
            return Result.ok(new WasmLane(Parser.parse(wasm), spec));
        } catch (RuntimeException e) {
            return Result.err("module_unparseable");
        }
    }

    /**
     * Calls the module with JSON arguments and returns its JSON result.
     *
     * <p>Every failure a module can cause is a value here, never an exception: a trap, a missing
     * export, a budget spent, a result that does not fit. The lane sits under the capability host,
     * and the host's contract is that a lane returns an outcome.
     */
    public Result<String, String> call(String argumentsJson, HostServices services) {
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(services, "services");
        StringBuilder output = new StringBuilder();
        AtomicLong fuel = new AtomicLong(spec.maxInstructions());

        Instance instance;
        try {
            instance = Instance.builder(module)
                    .withMemoryLimits(new MemoryLimits(1, spec.maxMemoryPages()))
                    .withImportValues(imports(output, services))
                    // Counting every instruction is what turns "please do not loop forever" into
                    // an arithmetic fact.
                    .withUnsafeExecutionListener((instruction, stack) -> {
                        if (fuel.decrementAndGet() < 0) {
                            throw new OutOfFuel();
                        }
                    })
                    .build();
        } catch (OutOfFuel e) {
            return Result.err("wasm_out_of_fuel");
        } catch (RuntimeException e) {
            // The commonest cause by far is an import the module wanted and was not granted.
            return Result.err("module_not_instantiable");
        }

        byte[] arguments = argumentsJson.getBytes(StandardCharsets.UTF_8);
        try {
            var alloc = instance.export("jclaw_alloc");
            var call = instance.export("jclaw_call");
            int pointer = (int) alloc.apply(arguments.length)[0];
            if (pointer <= 0 || pointer + arguments.length > spec.maxMemoryBytes()) {
                return Result.err("module_allocation_invalid");
            }
            instance.memory().write(pointer, arguments);

            long packed = call.apply(pointer, arguments.length)[0];
            int resultPointer = (int) (packed >>> 32);
            int resultLength = (int) (packed & 0xFFFFFFFFL);
            if (resultLength < 0 || resultLength > spec.maxOutputBytes()) {
                return Result.err("module_result_too_large");
            }
            if (resultPointer < 0 || (long) resultPointer + resultLength > spec.maxMemoryBytes()) {
                return Result.err("module_result_out_of_bounds");
            }
            String result = resultLength == 0 ? "" : instance.memory().readString(resultPointer, resultLength);
            return Result.ok(output.isEmpty() ? result : output + "\n" + result);
        } catch (OutOfFuel e) {
            return Result.err("wasm_out_of_fuel");
        } catch (RuntimeException e) {
            // A trap, a missing export, or a module that returned nonsense.
            return Result.err("module_trapped");
        }
    }

    /** Only the host functions the spec grants. Anything else the module wants, it does not get. */
    private ImportValues imports(StringBuilder output, HostServices services) {
        List<HostFunction> granted = new ArrayList<>();
        if (spec.grants("log")) {
            granted.add(new HostFunction("jclaw", "log",
                    List.of(ValueType.I32, ValueType.I32), List.of(),
                    (instance, args) -> {
                        String message = read(instance, (int) args[0], (int) args[1]);
                        if (output.length() + message.length() <= spec.maxOutputBytes()) {
                            output.append(message);
                        }
                        return null;
                    }));
        }
        if (spec.grants("read_file")) {
            granted.add(new HostFunction("jclaw", "read_file",
                    List.of(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
                    List.of(ValueType.I32),
                    (instance, args) -> new long[] {
                            copyOut(instance, services.readFile(read(instance, (int) args[0], (int) args[1])),
                                    (int) args[2], (int) args[3])}));
        }
        if (spec.grants("http_get")) {
            granted.add(new HostFunction("jclaw", "http_get",
                    List.of(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
                    List.of(ValueType.I32),
                    (instance, args) -> new long[] {
                            copyOut(instance, services.httpGet(read(instance, (int) args[0], (int) args[1])),
                                    (int) args[2], (int) args[3])}));
        }
        return ImportValues.builder().addFunction(granted.toArray(new HostFunction[0])).build();
    }

    private static String read(Instance instance, int pointer, int length) {
        if (pointer < 0 || length < 0) {
            return "";
        }
        return instance.memory().readString(pointer, length);
    }

    /** Writes a host answer into the module's buffer, returning its length or -1 when refused. */
    private static int copyOut(Instance instance, java.util.Optional<String> answer, int pointer, int capacity) {
        if (answer.isEmpty() || pointer < 0 || capacity <= 0) {
            return -1;
        }
        byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > capacity) {
            return -1;
        }
        instance.memory().write(pointer, bytes);
        return bytes.length;
    }
}

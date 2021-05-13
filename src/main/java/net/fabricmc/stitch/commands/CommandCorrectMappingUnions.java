package net.fabricmc.stitch.commands;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyFile;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyFile.MethodLine;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyLine;

public class CommandCorrectMappingUnions extends Command {
	public CommandCorrectMappingUnions() {
		super("correctMappingUnions");
	}

	@Override
	public String getHelpString() {
		return "<mappings> <base-namespace> <correcting-namespace...>";
	}

	@Override
	public boolean isArgumentCountValid(int count) {
		return count >= 3;
	}

	@Override
	public void run(String[] args) throws Exception {
		run(Paths.get(args[0]), args[1], Arrays.copyOfRange(args, 2, args.length));
	}

	public static void run(Path mappings, String baseNamespace, String... correctingNamespaces) throws IOException {
		System.out.println("Loading mapping file...");

		Mappings input;
		try (InputStream in = Files.newInputStream(mappings)) {
			input = MappingsProvider.readTinyMappings(in, false);
		}

		System.out.println("Finding mapping unions...");

		Map<String, Set<MethodEntry>> methodMappings = new HashMap<>();
		for (MethodEntry method : input.getMethodEntries()) {
			EntryTriple mapping = method.get(baseNamespace);
			if (mapping == null) continue; //Little naughty for the base to not contain everything but not to worry

			methodMappings.computeIfAbsent(mapping.getName().concat(mapping.getDesc()), k -> new HashSet<>()).add(method);
		}
		assert !methodMappings.containsKey(null);

		long unions = methodMappings.values().stream().filter(methods -> methods.size() > 1).count();
		if (unions > 0) {
			System.out.println("Handling " + unions + " mapping unions...");

			Map<EntryTriple, Map<String, String>> baseToName = new HashMap<>();
			for (Entry<String, Set<MethodEntry>> entry : methodMappings.entrySet()) {
				Set<MethodEntry> methods = entry.getValue();
				if (methods.size() < 2) continue;

				String baseName = entry.getKey();
				for (String namespace : correctingNamespaces) {
					int baseNameLength = baseName.indexOf('(');
					List<EntryTriple> changedNames = methods.stream().map(method -> method.get(namespace)).filter(mapping -> {
						if (mapping == null) return false; //Not present in this namespace
						return mapping.getName().length() != baseNameLength || !baseName.startsWith(mapping.getName());
					}).collect(Collectors.toList());

					String newName;
					switch (changedNames.size()) {
					case 0:
						continue; //None of the names are different in this namespace

					case 1:
						newName = changedNames.get(0).getName();
						break; //Only a single changed name, trivial

					default:
						EntryTriple first = changedNames.get(0);

						if (!changedNames.stream().map(EntryTriple::getName).allMatch(Predicate.isEqual(first.getName()))) {
							StringBuilder complaint = new StringBuilder("Inconsistent union naming, ").append(baseName).append(" ->");

							changedNames.stream().collect(Collectors.groupingBy(EntryTriple::getName)).forEach((name, responsible) -> {
								complaint.append("\n\t").append(name).append(':');
								responsible.forEach(method -> complaint.append("\n\t\t").append(method));
							});

							throw new IllegalStateException(complaint.toString());
						}

						newName = first.getName();
						break;
					}

					assert newName != null;
					for (MethodEntry method : methods) {
						EntryTriple namespaceMapping = method.get(namespace);
						if (namespaceMapping == null || changedNames.contains(namespaceMapping)) continue;

						baseToName.computeIfAbsent(method.get(baseNamespace), k -> new HashMap<>()).put(namespace, newName);
					}
				}
			}

			System.out.println("Rewriting " + baseToName.size() + " mappings...");

			TinyFile file = new TinyFile(mappings);
			try (BufferedWriter writer = Files.newBufferedWriter(mappings)) {
				writer.write(file.firstLine);
				writer.newLine();

				for (TinyLine line : file.lines()) {
					if (line.getClass() == MethodLine.class) {
						MethodLine methodLine;
						EntryTriple mapping = (methodLine = (MethodLine) line).get(baseNamespace);

						Map<String, String> namespaceToName = baseToName.get(mapping);
						if (namespaceToName != null) {
							EntryTriple primary = methodLine.get(file.nativeNamespace);

							writer.write("METHOD\t");
							writer.write(primary.getOwner());
							writer.write('\t');
							writer.write(primary.getDesc());

							file.getSortedNamespaces().forEach(namespace -> {
								try {
									writer.write('\t');
									String name = namespaceToName.getOrDefault(namespace, methodLine.get(namespace).getName());
									if (name != null) writer.write(name);
								} catch (IOException e) {
									throw new UncheckedIOException("Error writing " + primary + " for namespace " + namespace, e);
								}
							});

							writer.newLine();
							continue;
						}
					}

					writer.write(line.line);
					writer.newLine();
				}
			}
		} else {
			System.out.println("No unions found");
		}

	}
}
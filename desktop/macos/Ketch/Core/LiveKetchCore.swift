// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The app's `KetchCoreProtocol` over the generated `KetchCore` module (R9).
// Records, events and errors are converted here so the store and the views
// keep the types they were written against. The generated names and the app's
// names collide (`Upgrade`, `Reporter`, `KetchError`, …), so every generated
// type is spelled `KetchCore.…` and every app type `Ketch.…`.

import Foundation
import Synchronization

import class KetchCore.CancelToken
import struct KetchCore.Changelog
import struct KetchCore.Check
import protocol KetchCore.Decider
import enum KetchCore.Event
import struct KetchCore.Holder
import struct KetchCore.InstallOptions
import enum KetchCore.KetchError
import struct KetchCore.Package
import struct KetchCore.RegistryPackage
import protocol KetchCore.Reporter
import enum KetchCore.Stage
import struct KetchCore.Upgrade
import func KetchCore.packageForLink

/// The live core for one ketch root. Synchronous, like the protocol: the store
/// calls it off the main actor. The generated object is safe to share across
/// threads; this wrapper adds no mutable state of its own.
final class LiveKetchCore: KetchCoreProtocol, @unchecked Sendable {
    let root: URL
    private let core: NativeKetchCore

    /// How many packages a search or a browse returns. Discover opens with an
    /// empty query, which the core answers from the registry alone.
    private static let searchLimit: UInt32 = 200

    init(root: URL) {
        let core = NativeKetchCore(root: root.path(percentEncoded: false))
        self.core = core
        if let resolved = try? core.root() {
            self.root = URL(fileURLWithPath: resolved, isDirectory: true)
        } else {
            self.root = root
        }
    }

    func installed() throws -> [Ketch.InstalledPackage] {
        try call { try core.installed() }.map(Self.installedPackage)
    }

    func search(query: String) throws -> [Ketch.RegistryPackage] {
        let results = try call {
            try core.search(query: query, limit: Self.searchLimit, reporter: nil)
        }
        var mapped = results.known.map(Self.registryPackage)
        var seen = Set(mapped.map(\.name))
        for repository in results.repositories {
            let (name, repo) = Self.repositoryIdentity(repository.spec)
            guard seen.insert(name).inserted else { continue }
            mapped.append(
                Ketch.RegistryPackage(
                    name: name, repo: repo, description: repository.description, latest: nil))
        }
        return mapped
    }

    func outdated() throws -> [Ketch.Upgrade] {
        try call { try core.outdated(reporter: nil) }.map(Self.appUpgrade)
    }

    func install(
        spec: String, options: Ketch.InstallOptions, reporter: any Ketch.Reporter,
        decider: any Ketch.Decider, cancel: Ketch.CancelToken
    ) throws {
        let ffi = KetchCore.InstallOptions(
            force: options.force, prerelease: options.includePrereleases, link: true,
            requireChecksum: false, allowUnverified: false, bin: nil)
        _ = try call {
            try core.install(
                specs: [spec], options: ffi, reporter: BridgingReporter(reporter),
                decider: BridgingDecider(decider), cancel: ffiToken(cancel))
        }
    }

    func upgrade(
        names: [String], reporter: any Ketch.Reporter, decider: any Ketch.Decider,
        cancel: Ketch.CancelToken
    ) throws {
        _ = try call {
            try core.upgrade(
                names: names, reporter: BridgingReporter(reporter), decider: BridgingDecider(decider),
                cancel: ffiToken(cancel))
        }
    }

    func uninstall(names: [String], reporter: any Ketch.Reporter, cancel: Ketch.CancelToken) throws {
        _ = try call {
            try core.uninstall(
                names: names, reporter: BridgingReporter(reporter), decider: BridgingDecider(nil),
                cancel: ffiToken(cancel))
        }
    }

    func changelog(name: String, from: String?, to: String?) throws -> String {
        let range = try call {
            try core.changelogRange(package: name, from: from, to: to, reporter: nil)
        }
        if !range.isEmpty {
            return range.map(Self.markdown).joined(separator: "\n\n")
        }
        let one = try call { try core.changelog(package: name, version: to ?? from, reporter: nil) }
        return Self.markdown(one)
    }

    func doctor() throws -> [Finding] {
        try call { try core.doctor(reporter: nil) }.map(Self.finding)
    }

    func packageName(forLink link: String) throws -> String {
        try call { try KetchCore.packageForLink(url: link) }
    }

    // MARK: Records

    private static func installedPackage(_ package: KetchCore.Package) -> Ketch.InstalledPackage {
        let (scheme, repo) = splitSource(package.source)
        return Ketch.InstalledPackage(
            name: package.name, version: package.version, source: scheme, repo: repo, description: nil,
            path: package.prefix)
    }

    private static func registryPackage(_ package: KetchCore.RegistryPackage) -> Ketch.RegistryPackage {
        let (_, repo) = splitSource(package.source)
        return Ketch.RegistryPackage(
            name: package.name, repo: repo ?? package.source, description: package.description,
            latest: package.latest)
    }

    private static func appUpgrade(_ upgrade: KetchCore.Upgrade) -> Ketch.Upgrade {
        // A pin with no lock file still has to show as held: the views treat
        // a nil `heldBy` as something `upgrade` would install.
        let heldBy = upgrade.heldBy ?? (upgrade.pinned ? "a pin" : nil)
        return Ketch.Upgrade(name: upgrade.name, from: upgrade.installed, to: upgrade.latest, heldBy: heldBy)
    }

    private static func finding(_ check: KetchCore.Check) -> Finding {
        let severity: Finding.Severity
        switch check.outcome {
        case .ok: severity = .ok
        case .warn: severity = .warning
        case .fail: severity = .error
        }
        return Finding(
            id: check.name, severity: severity,
            message: check.detail.isEmpty ? check.name : check.detail, fix: check.fix)
    }

    /// `github:owner/repo` becomes scheme `github` and id `owner/repo`.
    private static func splitSource(_ source: String) -> (scheme: String, id: String?) {
        guard let colon = source.firstIndex(of: ":") else { return (source, nil) }
        let scheme = String(source[..<colon])
        let id = String(source[source.index(after: colon)...])
        return (scheme, id.isEmpty ? nil : id)
    }

    /// A search hit the registry does not know: the spec is `github:owner/repo`.
    private static func repositoryIdentity(_ spec: String) -> (name: String, repo: String) {
        let (_, id) = splitSource(spec)
        let repo = id ?? spec
        let name = repo.split(separator: "/").last.map(String.init) ?? repo
        return (name, repo)
    }

    private static func markdown(_ entry: KetchCore.Changelog) -> String {
        let title = entry.heading ?? "## \(entry.version)"
        if entry.body.isEmpty { return title }
        return "\(title)\n\n\(entry.body)"
    }

    /// The core's stage list has no `hooks` and calls linking `installing`.
    /// Trust is a check, so it shares the verify slice of the activity bar.
    fileprivate static func stage(_ stage: KetchCore.Stage) -> Ketch.Stage {
        switch stage {
        case .resolving: .resolve
        case .downloading: .download
        case .verifying, .trusting: .verify
        case .extracting: .extract
        case .installing: .link
        }
    }

    private static func appError(_ error: KetchCore.KetchError) -> Ketch.KetchError {
        switch error {
        case .Busy(let pid): .busy(pid: pid ?? 0)
        case .Cancelled: .cancelled
        case .NotFound(let name): .notFound(name: name)
        case .Network(let message): .network(message: message)
        case .Verification(let message): .verification(message: message)
        case .Other(let message): .other(message: message)
        }
    }

    private func call<T>(_ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch let error as KetchCore.KetchError {
            throw Self.appError(error)
        } catch {
            throw Ketch.KetchError.other(message: error.localizedDescription)
        }
    }

    private func ffiToken(_ cancel: Ketch.CancelToken) -> KetchCore.CancelToken {
        let box = FFICancel(KetchCore.CancelToken())
        if cancel.isCancelled { box.cancel() }
        cancel.onCancel { box.cancel() }
        return box.token
    }
}

/// Holds the generated token so a `@Sendable` cancel handler can call it.
/// The token itself is safe from any thread; the generated class is not marked.
private final class FFICancel: @unchecked Sendable {
    let token: KetchCore.CancelToken

    init(_ token: KetchCore.CancelToken) { self.token = token }

    func cancel() { token.cancel() }
}

/// Forwards generated events as the app's `CoreEvent`s. Download progress is
/// keyed by task id; `began` names the package that id belongs to.
private final class BridgingReporter: KetchCore.Reporter, @unchecked Sendable {
    private let forward: any Ketch.Reporter
    private let labels = Mutex<[UInt64: String]>([:])

    init(_ forward: any Ketch.Reporter) { self.forward = forward }

    func event(event: KetchCore.Event) {
        switch event {
        case .step(let package, let stage):
            forward.event(.step(package: package, stage: LiveKetchCore.stage(stage)))
        case .status(let verb, let detail):
            sendStatus(verb, detail)
        case .success(let verb, let detail):
            sendStatus(verb, detail)
        case .warn(let detail):
            forward.event(.warning(detail))
        case .note(let detail):
            if !detail.isEmpty { forward.event(.status(detail)) }
        case .debug(_):
            // Verbose detail stays in the core's log. The activity view is not that log.
            break
        case .began(let id, let task):
            switch task {
            case .download(let label, _):
                labels.withLock { $0[id] = label }
            case .activity(let message):
                if !message.isEmpty { forward.event(.status(message)) }
            case .batch:
                break
            case .counter(_, _, _):
                break
            }
        case .sized(let id, let name, let total):
            let package = remember(id, name: name)
            forward.event(.progress(package: package, done: 0, total: total))
        case .progress(let id, let done, let total):
            guard let package = labels.withLock({ $0[id] }) else { return }
            forward.event(.progress(package: package, done: done, total: total))
        case .ended(let id, let message):
            labels.withLock { $0[id] = nil }
            if let message, !message.isEmpty { forward.event(.status(message)) }
        case .abandoned(let id):
            labels.withLock { $0[id] = nil }
        }
    }

    private func remember(_ id: UInt64, name: String) -> String {
        labels.withLock { labels in
            if labels[id] == nil { labels[id] = name }
            return labels[id] ?? name
        }
    }

    private func sendStatus(_ verb: String, _ detail: String) {
        let text = [verb, detail].filter { !$0.isEmpty }.joined(separator: " ")
        if !text.isEmpty { forward.event(.status(text)) }
    }
}

/// Binary choice is the only question the app's decider can ask. Stopping a
/// process or deleting a leftover would decide for the person, so both decline,
/// which is what the core does when a caller passes no decider.
private final class BridgingDecider: KetchCore.Decider, @unchecked Sendable {
    private let decider: (any Ketch.Decider)?

    init(_ decider: (any Ketch.Decider)?) { self.decider = decider }

    func chooseBinary(package: String, candidates: [String]) -> UInt32? {
        guard let pick = decider?.chooseBinary(package: package, candidates: candidates), pick >= 0 else {
            return nil
        }
        return UInt32(exactly: pick)
    }

    func stopProcesses(holders _: [KetchCore.Holder]) -> Bool { false }

    func confirmRemoveLeftover(path _: String) -> Bool { false }
}

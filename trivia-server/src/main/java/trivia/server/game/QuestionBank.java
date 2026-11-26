package trivia.server.game;

import trivia.shared.model.Question;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QuestionBank {
    private final List<Question> questions;

    public QuestionBank() {
        List<Question> built = new ArrayList<>();
        built.add(new Question(
                "Which protocol is connection-oriented and reliable?",
                List.of("UDP", "TCP", "ICMP", "ARP"),
                1));
        built.add(new Question(
                "Which Java class listens for incoming TCP connections using traditional I/O?",
                List.of("DatagramSocket", "ServerSocket", "MulticastSocket", "Selector"),
                1));
        built.add(new Question(
                "What is the IPv4 multicast address range?",
                List.of("1.0.0.0–126.0.0.0", "192.168.0.0–192.168.255.255", "224.0.0.0–239.255.255.255", "240.0.0.0–255.255.255.255"),
                2));
        built.add(new Question(
                "Which NIO component lets one thread monitor many channels?",
                List.of("ByteBuffer", "FileChannel", "Selector", "PrintWriter"),
                2));
        built.add(new Question(
                "Which HTTP method retrieves a resource without creating one?",
                List.of("POST", "PUT", "DELETE", "GET"),
                3));
        built.add(new Question(
                "ExecutorService is mainly used to:",
                List.of("Encrypt sockets", "Manage a pool of worker threads", "Resolve DNS names", "Frame UDP packets"),
                1));
        this.questions = Collections.unmodifiableList(built);
    }

    public List<Question> pick(int count) {
        List<Question> copy = new ArrayList<>(questions);
        Collections.shuffle(copy);
        if (count >= copy.size()) {
            return copy;
        }
        return new ArrayList<>(copy.subList(0, count));
    }
}

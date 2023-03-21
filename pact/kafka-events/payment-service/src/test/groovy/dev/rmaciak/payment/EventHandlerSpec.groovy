package dev.rmaciak.payment

import au.com.dius.pact.consumer.groovy.messaging.PactMessageBuilder
import au.com.dius.pact.core.model.messaging.Message
import dev.rmaciak.payment.domain.PaymentRepository
import dev.rmaciak.payment.events.OrderCreatedEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.spock.Testcontainers
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static dev.rmaciak.payment.domain.PaymentStatus.FINISHED
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE

@SpringBootTest(classes = [PaymentApplication], webEnvironment = NONE)
@Testcontainers
@ActiveProfiles("test")
class EventHandlerSpec extends Specification {

    @Shared
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"))

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate

    @Autowired
    private PaymentRepository paymentRepository

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        kafka.start()
        registry.add("spring.kafka.bootstrap-servers", () -> kafka.getBootstrapServers())
    }

    def "should initiate payment on OrderCreatedEvent"() {
        given:
        def eventFlow = new PactMessageBuilder().call {
            serviceConsumer 'payment-service'
            hasPactWith 'order-service'

            expectsToReceive 'OrderCreatedEvent'
            withContent(contentType: 'application/json') {
                eventId uuid()
                occurredAt datetime()

                orderId uuid()
                accountId uuid()
                total decimal()
                isPaid true
            }
        }

        when:
        eventFlow.run { Message message ->
            kafkaTemplate.send('OrderCreated', message.contentsAsBytes())
        }

        then:
        new PollingConditions(timeout: 10, initialDelay: 1.5, factor: 1.25).eventually {
            def payments = paymentRepository.getAll()
            assert payments.size() == 1
            assert payments[0].paymentStatus() == FINISHED
        }
    }
}
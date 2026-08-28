package org.example.jobpostingcrawler.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMqConfig {

    private final CrawlerProperties crawlerProperties;

    @Bean
    public TopicExchange jobPostingExchange() {
        return new TopicExchange(crawlerProperties.rabbitmq().exchange());
    }

    @Bean
    public Queue jobPostingQueue() {
        return new Queue(crawlerProperties.rabbitmq().queue(), true);
    }

    @Bean
    public Binding jobPostingBinding(Queue jobPostingQueue, TopicExchange jobPostingExchange) {
        return BindingBuilder.bind(jobPostingQueue)
                .to(jobPostingExchange)
                .with(crawlerProperties.rabbitmq().routingKey());
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         JacksonJsonMessageConverter jacksonJsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jacksonJsonMessageConverter);
        return rabbitTemplate;
    }
}
